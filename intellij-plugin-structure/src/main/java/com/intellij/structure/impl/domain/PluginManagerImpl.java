package com.intellij.structure.impl.domain;

import com.google.common.base.Supplier;
import com.intellij.structure.domain.Plugin;
import com.intellij.structure.domain.PluginDependency;
import com.intellij.structure.domain.PluginManager;
import com.intellij.structure.errors.IncorrectPluginException;
import com.intellij.structure.impl.utils.Pair;
import com.intellij.structure.impl.utils.Ref;
import com.intellij.structure.impl.utils.StringUtil;
import com.intellij.structure.impl.utils.validators.PluginXmlValidator;
import com.intellij.structure.impl.utils.validators.Validator;
import com.intellij.structure.impl.utils.xml.JDOMUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jdom2.Document;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;


/**
 * @author Sergey Patrikeev
 */
public class PluginManagerImpl extends PluginManager {

  private static final String PLUGIN_XML = "plugin.xml";
  private static final Pattern LIB_JAR_REGEX = Pattern.compile("([^/]+/)?lib/([^/]+\\.(jar|zip))");


  private static final Pattern XML_IN_META_INF_PATTERN = Pattern.compile("([^/]*/)?META-INF/(([^/]+/)*(\\w|\\-)+\\.xml)");

  /**
   * <p>Contains all the .xml files under META-INF/ directory and subdirectories.</p> It consists of the following
   * entries: (file path relative to META-INF/ dir) TO pair of (full URL path of the file) and (corresponding Document)
   * <p>It will be used later to resolve optional descriptors.</p>
   */
  private final Map<String, Pair<String, Document>> myRootXmlDocuments = new HashMap<String, Pair<String, Document>>();

  private File myPluginFile;

  public static boolean isJarOrZip(@NotNull File file) {
    if (file.isDirectory()) {
      return false;
    }
    final String name = file.getName();
    return StringUtil.endsWithIgnoreCase(name, ".jar") || StringUtil.endsWithIgnoreCase(name, ".zip");
  }

  @NotNull
  public static String getFileEscapedUri(@NotNull File file) {
    return StringUtil.replace(file.toURI().toASCIIString(), "!", "%21");
  }

  /**
   * <p>Searches the descriptor on the {@code filePath} relative to META-INF/ directory.</p>
   * Example:
   * <p>If {@code filePath} == plugin.xml => loads ..META-INF/plugin.xml</p>
   * <p>If {@code filePath} == relative/plugin.xml => loads ..META-INF/relative/plugin.xml</p>
   * <p>If {@code filePath} == ../brotherDir/optional.xml => loads ..META-INF/../brotherDir/plugin.xml</p>
   * and so on...
   *
   * @param file      plugin file
   * @param filePath  descriptor file path relative to META-INF/ directory
   * @param validator problems controller
   * @return plugin descriptor
   * @throws IncorrectPluginException if plugin is broken
   */
  @Nullable
  private Plugin loadDescriptor(@NotNull final File file, @NotNull String filePath, @NotNull Validator validator) throws IncorrectPluginException {
    filePath = StringUtil.toSystemIndependentName(filePath);

    Plugin descriptor;

    if (file.isDirectory()) {
      descriptor = loadDescriptorFromDir(file, filePath, validator);
    } else if (file.exists() && isJarOrZip(file)) {
      descriptor = loadDescriptorFromZipOrJarFile(file, filePath, validator);
    } else {
      if (!file.exists()) {
        validator.onIncorrectStructure("Plugin file is not found " + file);
      } else {
        validator.onIncorrectStructure("Incorrect plugin file type " + file + ". Should be a .zip or .jar archive or a directory.");
      }
      return null;
    }

    if (descriptor != null) {
      resolveOptionalDescriptors(file, filePath, (PluginImpl) descriptor, validator);
    }

    if (descriptor == null) {
      validator.onMissingFile("META-INF/" + filePath + " is not found");
    }

    return descriptor;
  }

  private void resolveOptionalDescriptors(@NotNull File file,
                                          @NotNull String filePath,
                                          @NotNull PluginImpl descriptor,
                                          @NotNull Validator parentValidator) throws IncorrectPluginException {
    Map<PluginDependency, String> optionalConfigs = descriptor.getOptionalDependenciesConfigFiles();
    if (!optionalConfigs.isEmpty()) {
      Map<String, PluginImpl> descriptors = new HashMap<String, PluginImpl>();

      for (Map.Entry<PluginDependency, String> entry : optionalConfigs.entrySet()) {
        String optFilePath = entry.getValue();

        if (StringUtil.equal(filePath, optFilePath)) {
          parentValidator.onIncorrectStructure("Plugin has recursive config dependencies for descriptor " + filePath);
        }

        final String original = optFilePath;
        if (optFilePath.startsWith("/META-INF/")) {
          optFilePath = StringUtil.trimStart(optFilePath, "/META-INF/");
        }

        Pair<String, Document> xmlPair = myRootXmlDocuments.get(optFilePath);
        if (xmlPair != null) {
          try {
            URL url = new URL(xmlPair.getFirst());
            Document document = xmlPair.getSecond();
            PluginImpl optDescriptor = new PluginImpl(myPluginFile);
            optDescriptor.readExternal(document, url, parentValidator.ignoreMissingConfigElement());
            descriptors.put(original, optDescriptor);
          } catch (MalformedURLException e) {
            parentValidator.onCheckedException("Unable to read META-INF/" + optFilePath, e);
          }
        } else {
          //don't complain if the file is not found and don't complain if it has incorrect .xml structure
          Validator optValidator = parentValidator.ignoreMissingConfigElement().ignoreMissingFile();

          PluginImpl optDescriptor = (PluginImpl) loadDescriptor(file, optFilePath, optValidator);

//          TODO: in IDEA there is one more attempt to load optional descriptor
//          URL resource = PluginManagerCore.class.getClassLoader().getResource(META_INF + '/' + optionalDescriptorName);
//          if (resource != null) {
//            optionalDescriptor = loadDescriptorFromResource(resource);
//          }

          if (optDescriptor != null) {
            descriptors.put(original, optDescriptor);
          } else {
            System.err.println("Optional descriptor META-INF/" + optFilePath + " is not found");
          }
        }
      }

      descriptor.setOptionalDescriptors(descriptors);
    }
  }

  //filePath is relative to META-INF/ => should resolve it properly

  /**
   * Checks that the given {@code entry} corresponds to the sought-for file specified with {@code filePath}.
   *
   * @param entry               current entry in the overlying traversing of zip file
   * @param filePath            sought-for file, path is relative to META-INF/ directory
   * @param rootUrl             url corresponding to the root of the zip file from which this {@code entry} come
   * @param validator           problems resolver
   * @param entryStreamSupplier supplies the input stream for this entry if needed
   * @return sought-for descriptor or null
   * @throws IncorrectPluginException if incorrect plugin structure
   */
  @Nullable
  private Plugin loadDescriptorFromEntry(@NotNull ZipEntry entry,
                                         @NotNull String filePath,
                                         @NotNull String rootUrl,
                                         @NotNull Validator validator,
                                         @NotNull Supplier<InputStream> entryStreamSupplier) throws IncorrectPluginException {
    Matcher xmlMatcher = XML_IN_META_INF_PATTERN.matcher(entry.getName());
    if (xmlMatcher.matches()) {
      final String xmlUrl = rootUrl + entry.getName();
      String name = xmlMatcher.group(2);

      Document document;
      URL url;
      try {
        //get input stream for this entry
        InputStream stream = entryStreamSupplier.get();
        if (stream == null) {
          return null;
        }
        document = JDOMUtil.loadDocument(stream);
        url = new URL(xmlUrl);
      } catch (Exception e) {
        //check if an exception happened on the sought-for entity
        if (StringUtil.equal(name, filePath)) {
          validator.onCheckedException("Unable to read META-INF/" + name, e);
        }
        System.err.println("Unable to read an entry `" + entry.getName() + "` because " + e.getLocalizedMessage());
        return null;
      }

      if (StringUtil.equal(name, filePath)) {
        PluginImpl descriptor = new PluginImpl(myPluginFile);
        descriptor.readExternal(document, url, validator);
        return descriptor;
      } else {
        //add this .xml for the future check
        myRootXmlDocuments.put(name, Pair.create(xmlUrl, document));
      }
    } else if (filePath.startsWith("../")) {
      //for example filePath == ../brotherDir/opt.xml
      // => absolute path == <in_zip_path>/META-INF/../brotherDir/opt.xml
      //                  == <in_zip_path>/brotherDir/opt.xml
      filePath = StringUtil.trimStart(filePath, "../");
      if (filePath.startsWith("../")) {
        //we don't support ../../opts/opt.xml paths yet (is it needed?)
        return null;
      }
      if (entry.getName().endsWith(filePath)) {
        //this xml is probably what is searched for

        InputStream is = entryStreamSupplier.get();
        if (is == null) {
          return null;
        }
        try {
          Document document = JDOMUtil.loadDocument(is);
          String xmlUrl = rootUrl + entry.getName();
          URL url = new URL(xmlUrl);

          PluginImpl descriptor = new PluginImpl(myPluginFile);
          descriptor.readExternal(document, url, validator);
          return descriptor;
        } catch (RuntimeException e) {
          //rethrow a RuntimeException but wrap a checked exception
          throw e;
        } catch (Exception e) {
          validator.onCheckedException("Unable to read META-INF/" + filePath, e);
          return null;
        }
      }
    }
    return null;
  }

  @Nullable
  private Plugin loadFromZipStream(@NotNull final ZipInputStream zipStream,
                                   @NotNull String zipRootUrl,
                                   @NotNull String filePath,
                                   @NotNull Validator validator) throws IncorrectPluginException {
    Plugin descriptor = null;

    try {
      ZipEntry entry;
      while ((entry = zipStream.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }

        Plugin inRoot = loadDescriptorFromEntry(entry, filePath, zipRootUrl, validator, new Supplier<InputStream>() {
          @Override
          public InputStream get() {
            return zipStream;
          }
        });

        if (inRoot != null) {
          if (descriptor != null) {
            validator.onIncorrectStructure("Multiple META-INF/" + filePath + " found");
            return null;
          }
          descriptor = inRoot;
        }
      }
    } catch (IOException e) {
      validator.onCheckedException("Unable to load META-INF/" + filePath, e);
      return null;
    }

    if (descriptor == null) {
      validator.onMissingFile("META-INF/" + filePath + " is not found");
    }

    return descriptor;
  }

  @Nullable
  private Plugin loadDescriptorFromZipOrJarFile(@NotNull final File file, @NotNull final String filePath, @NotNull final Validator validator) throws IncorrectPluginException {
    final String zipRootUrl = "jar:" + getFileEscapedUri(file) + "!/";

    Plugin descriptorRoot = null;
    Plugin descriptorInner = null;

    ZipFile zipFile = null;
    try {
      zipFile = new ZipFile(file);
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        final ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) {
          continue;
        }

        //check if this .zip file is an archive of the .jar file
        //in this case it contains the following entry: a.zip!/b.jar!/META-INF/plugin.xml
        if (entry.getName().endsWith(".jar")) {
          if (entry.getName().indexOf('/') == -1) {
            //this is in-root .jar file which will be extracted by the IDE
            ZipInputStream inRootJar = new ZipInputStream(zipFile.getInputStream(entry));
            Plugin plugin = loadFromZipStream(inRootJar, "jar:" + zipRootUrl + entry.getName() + "!/", filePath, validator.ignoreMissingFile());
            if (plugin != null) {
              if (descriptorRoot != null) {
                validator.onIncorrectStructure("Multiple META-INF/" + filePath + " found in the root of the plugin");
                return null;
              }
              descriptorRoot = plugin;
            }
          }
        }

        final ZipFile finalZipFile = zipFile;
        final Ref<IOException> maybeException = Ref.create();
        Plugin inRoot = loadDescriptorFromEntry(entry, filePath, zipRootUrl, validator.ignoreMissingFile(), new Supplier<InputStream>() {
          @Override
          public InputStream get() {
            try {
              return finalZipFile.getInputStream(entry);
            } catch (IOException e) {
              maybeException.set(e);
              return null;
            }
          }
        });
        if (!maybeException.isNull()) {
          throw maybeException.get();
        }

        if (inRoot != null) {
          if (descriptorRoot != null) {
            //TODO: is it necessary to throw an exception?
            System.err.println("Multiple META-INF/" + filePath + " found in the root of the plugin");
//            validator.onIncorrectStructure("Multiple META-INF/" + filePath + " found in the root of the plugin");
//            return null;
          }
          descriptorRoot = inRoot;
          continue;
        }

        if (LIB_JAR_REGEX.matcher(entry.getName()).matches()) {
          ZipInputStream inner = new ZipInputStream(zipFile.getInputStream(entry));
          Plugin innerDescriptor = loadFromZipStream(inner, "jar:" + zipRootUrl + entry.getName() + "!/", filePath, validator.ignoreMissingFile());
          if (innerDescriptor != null) {
            descriptorInner = innerDescriptor;
          }
        }
      }
    } catch (IOException e) {
      validator.onCheckedException("Unable to read plugin file " + file, e);
      return null;
    } finally {
      try {
        if (zipFile != null) {
          zipFile.close();
        }
      } catch (IOException ignored) {
      }
    }

    //TODO: write a test: in-root-descriptor takes precedence over in-lib-descriptor

    //in-root descriptor takes precedence over other descriptors, so don't throw
    //"Multiple plugin.xml" if they are found in the <root>/META-INF/plugin.xml and <root>/lib/some.jar/META-INF/plugin.xml
    if (descriptorRoot != null) {

      //TODO: rewrite
      if (descriptorInner != null) {
        //some plugins have logo-file in the lib-descriptor
        if (descriptorInner.getVendorLogo() != null) {
          ((PluginImpl) descriptorRoot).setLogoContent(descriptorInner.getVendorLogo());
        }
      }

      return descriptorRoot;
    }

    if (descriptorInner != null) {
      return descriptorInner;
    }

    //TODO: print illustrative message about why it's not found
    //(maybe a plugin has an incorrect structure, e.g. a.zip/plugin_name/plugin.jar - without lib/ directory.

    validator.onMissingFile("META-INF/" + filePath + " is not found");
    return null;
  }

  @Nullable
  private Plugin loadDescriptorFromDir(@NotNull final File dir, @NotNull String filePath, @NotNull Validator validator) throws IncorrectPluginException {
    File descriptorFile = new File(dir, "META-INF" + File.separator + StringUtil.toSystemDependentName(filePath));
    if (descriptorFile.exists()) {

      Collection<File> allXmlUnderMetaInf = FileUtils.listFiles(descriptorFile.getParentFile(), new String[]{"xml"}, true);
      for (File xml : allXmlUnderMetaInf) {
        InputStream inputStream = null;
        try {
          inputStream = FileUtils.openInputStream(xml);
          Document document = JDOMUtil.loadDocument(inputStream);
          String uri = getFileEscapedUri(xml);
          myRootXmlDocuments.put(xml.getName(), Pair.create(uri, document));
        } catch (Exception e) {
          if (StringUtil.equal(xml.getName(), StringUtil.getFileName(filePath))) {
            validator.onCheckedException("Unable to read .xml file META-INF/" + filePath, e);
          }
        } finally {
          IOUtils.closeQuietly(inputStream);
        }
      }

      PluginImpl descriptor = new PluginImpl(myPluginFile);
      try {
        descriptor.readExternal(descriptorFile.toURI().toURL(), validator);
      } catch (MalformedURLException e) {
        validator.onCheckedException("File " + dir + " contains invalid plugin descriptor " + filePath, e);
        return null;
      }
      return descriptor;
    }
    return loadDescriptorFromLibDir(dir, filePath, validator);
  }

  @Nullable
  private Plugin loadDescriptorFromLibDir(@NotNull final File dir, @NotNull String filePath, @NotNull Validator validator) throws IncorrectPluginException {
    File libDir = new File(dir, "lib");
    if (!libDir.isDirectory()) {
      validator.onMissingFile("Plugin `lib` directory is not found");
      return null;
    }

    final File[] files = libDir.listFiles();
    if (files == null || files.length == 0) {
      validator.onIncorrectStructure("Plugin `lib` directory is empty");
      return null;
    }
    //move plugin-jar to the beginning: Sample.jar goes first (if Sample is a plugin name)
    Arrays.sort(files, new Comparator<File>() {
      @Override
      public int compare(@NotNull File o1, @NotNull File o2) {
        if (o2.getName().startsWith(dir.getName())) return Integer.MAX_VALUE;
        if (o1.getName().startsWith(dir.getName())) return -Integer.MAX_VALUE;
        if (o2.getName().startsWith("resources")) return -Integer.MAX_VALUE;
        if (o1.getName().startsWith("resources")) return Integer.MAX_VALUE;
        return 0;
      }
    });

    Plugin descriptor = null;

    for (final File f : files) {
      if (isJarOrZip(f)) {
        descriptor = loadDescriptorFromZipOrJarFile(f, filePath, validator.ignoreMissingFile());
        if (descriptor != null) {
          //is it necessary to check that only one META-INF/plugin.xml is presented?
          break;
        }
      } else if (f.isDirectory()) {
        Plugin descriptor1 = loadDescriptorFromDir(f, filePath, validator.ignoreMissingFile());
        if (descriptor1 != null) {
          if (descriptor != null) {
            validator.onIncorrectStructure("Multiple META-INF/" + filePath + " found");
            return null;
          }
          descriptor = descriptor1;
        }
      }
    }

    if (descriptor == null) {
      validator.onMissingFile("Unable to find valid META-INF/" + filePath);
    }

    return descriptor;
  }

  @NotNull
  @Override
  public Plugin createPlugin(@NotNull File pluginFile, boolean validatePluginXml) throws IncorrectPluginException {
    Validator validator = new PluginXmlValidator();
    if (!validatePluginXml) {
      validator = validator.ignoreMissingConfigElement();
    }

    myPluginFile = pluginFile;

    PluginImpl descriptor = (PluginImpl) loadDescriptor(pluginFile, PLUGIN_XML, validator);
    if (descriptor != null) {
      return descriptor;
    }
    //assert that PluginXmlValidator has thrown an appropriate exception
    throw new AssertionError("Unable to create plugin from " + pluginFile);
  }



}