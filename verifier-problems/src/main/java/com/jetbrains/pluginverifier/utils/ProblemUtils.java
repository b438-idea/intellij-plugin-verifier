package com.jetbrains.pluginverifier.utils;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.hash.Hashing;
import com.jetbrains.pluginverifier.format.UpdateInfo;
import com.jetbrains.pluginverifier.problems.*;
import com.jetbrains.pluginverifier.results.ProblemSet;
import com.jetbrains.pluginverifier.results.ResultsElement;
import com.jetbrains.pluginverifier.results.plugin.PluginCheckResult;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

public class ProblemUtils {

  private static final JAXBContext JAXB_CONTEXT;

  static {
    try {
      //if necessary add problem here (and add default constructor for it)
      JAXB_CONTEXT = JAXBContext.newInstance(
          //--------PROBLEMS--------
          ClassNotFoundProblem.class,
          DuplicateClassProblem.class,
          FailedToReadClassProblem.class,
          IllegalMethodAccessProblem.class,
          IncompatibleClassChangeProblem.class,
          MethodNotFoundProblem.class,
          MethodNotImplementedProblem.class,
          OverridingFinalMethodProblem.class,

          //--------RESULT-ELEMENTS--------
          ResultsElement.class,
          UpdateInfo.class,
          PluginCheckResult.class,
          ProblemSet.class
      );
    } catch (JAXBException e) {
      throw FailUtil.fail(e);
    }
  }

  private static Marshaller createMarshaller() {
    try {
      return JAXB_CONTEXT.createMarshaller();
    } catch (JAXBException e) {
      throw new RuntimeException("Failed to create marshaller");
    }
  }

  private static Unmarshaller createUnmarshaller() {
    try {
      return JAXB_CONTEXT.createUnmarshaller();
    } catch (JAXBException e) {
      throw new RuntimeException("Failed to create unmarshaller");
    }
  }

  @NotNull
  private static String problemToString(@NotNull Problem problem, boolean format) {
    try {
      Marshaller marshaller = JAXB_CONTEXT.createMarshaller();

      if (format) {
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
      }

      marshaller.setProperty(Marshaller.JAXB_FRAGMENT, true);

      StringWriter writer = new StringWriter();

      marshaller.marshal(problem, writer);

      return writer.toString();
    } catch (JAXBException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  public static ResultsElement loadProblems(@NotNull File xml) throws IOException {
    return (ResultsElement) loadFromFile(xml);
  }

  @NotNull
  public static PluginCheckResult loadPluginCheckResults(@NotNull File xml) throws IOException {
    return (PluginCheckResult) loadFromFile(xml);
  }

  @NotNull
  private static Object loadFromFile(@NotNull File xml) throws IOException {
    InputStream inputStream = new BufferedInputStream(new FileInputStream(xml));

    try {
      return loadObject(inputStream);
    } finally {
      IOUtils.closeQuietly(inputStream);
    }
  }

  public static void saveProblems(@NotNull File output,
                                  @NotNull String ide,
                                  @NotNull Map<UpdateInfo, Collection<Problem>> problems)
      throws IOException {
    ResultsElement resultsElement = new ResultsElement();

    resultsElement.setIde(ide);
    resultsElement.initFromMap(problems);

    marshallObject(output, resultsElement);
  }

  public static void savePluginCheckResult(@NotNull File output,
                                           @NotNull Map<String, ProblemSet> ideToProblems,
                                           @NotNull UpdateInfo updateInfo) throws IOException {
    savePluginCheckResult(output, new PluginCheckResult(updateInfo, ideToProblems));
  }

  public static void savePluginCheckResult(@NotNull File output,
                                           @NotNull PluginCheckResult pluginCheckResult) throws IOException {
    marshallObject(output, pluginCheckResult);
  }

  private static void marshallObject(@NotNull File output, @NotNull Object o)
      throws IOException {
    Marshaller marshaller = createMarshaller();

    try {
      marshaller.setProperty(Marshaller.JAXB_FRAGMENT, true);
      marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

      marshaller.marshal(o, output);
    } catch (JAXBException e) {
      throw new IOException(e);
    }
  }

  @NotNull
  private static Object loadObject(@NotNull InputStream inputStream) throws IOException {
    Unmarshaller unmarshaller = createUnmarshaller();

    try {
      return unmarshaller.unmarshal(inputStream);
    } catch (JAXBException e) {
      throw new IOException(e);
    }
  }


  @NotNull
  public static List<Problem> sortProblems(@NotNull Collection<Problem> problems) {
    List<Problem> res = new ArrayList<Problem>(problems);
    Collections.sort(res, new ToStringProblemComparator());
    return res;
  }

  @NotNull
  public static String hash(@NotNull Problem problem) {
    String s = problemToString(problem, false);
    return Hashing.md5().hashString(s, Charset.defaultCharset()).toString();
  }

  /**
   * In DESCENDING order of versions
   */
  public static Collection<UpdateInfo> sortUpdates(@NotNull Collection<UpdateInfo> updateInfos) {
    Collections.sort(new ArrayList<UpdateInfo>(updateInfos), new Comparator<UpdateInfo>() {
      @Override
      public int compare(UpdateInfo o1, UpdateInfo o2) {
        String p1 = o1.getPluginId() != null ? o1.getPluginId() : "#" + o1.getUpdateId();
        String p2 = o2.getPluginId() != null ? o2.getPluginId() : "#" + o2.getUpdateId();
        if (!p1.equals(p2)) {
          return p1.compareTo(p2); //compare lexicographically
        }
        return VersionComparatorUtil.compare(o2.getVersion(), o1.getVersion());
      }
    });
    return updateInfos;
  }

  /**
   * Transforms {@literal Map<Update -> [Problems]>  TO Multimap<Problem -> [Updates]>}
   */
  @NotNull
  public static Multimap<Problem, UpdateInfo> rearrangeProblemsMap(@NotNull Map<UpdateInfo, Collection<Problem>> currentProblemsMap) {
    Multimap<Problem, UpdateInfo> currentProblemsToUpdates = ArrayListMultimap.create();

    //rearrange existing map: Map<Problem -> [plugin ids]>
    for (Map.Entry<UpdateInfo, Collection<Problem>> entry : currentProblemsMap.entrySet()) {
      for (Problem problem : entry.getValue()) {
        currentProblemsToUpdates.put(problem, entry.getKey());
      }
    }
    return currentProblemsToUpdates;
  }
}
