/*
 *  Copyright 2010.
 */
package ro.isdc.wro.extensions.processor.algorithm.jsonhpack;

import java.io.InputStream;

import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.isdc.wro.WroRuntimeException;
import ro.isdc.wro.extensions.script.RhinoScriptBuilder;
import ro.isdc.wro.extensions.script.RhinoUtils;
import ro.isdc.wro.util.StopWatch;
import ro.isdc.wro.util.WroUtil;


/**
 * The underlying implementation use the json.hpack project: {@link https://github.com/WebReflection/json.hpack}.
 *
 * @author Alex Objelean
 * @since 1.3.8
 */
public class JsonHPack {
  private static final Logger LOG = LoggerFactory.getLogger(JsonHPack.class);
  private ScriptableObject scope;

  /**
   * Initialize script builder for evaluation.
   */
  private RhinoScriptBuilder initScriptBuilder() {
    try {
      RhinoScriptBuilder builder = null;
      if (scope == null) {
        builder = RhinoScriptBuilder.newClientSideAwareChain().evaluateChain(
          getScriptAsStream(), "json.hpack.js");
        scope = builder.getScope();
      } else {
        builder = RhinoScriptBuilder.newChain(scope);
      }
      return builder;
    } catch (final Exception e) {
      LOG.error("Processing error:" + e.getMessage(), e);
      throw new WroRuntimeException("Processing error", e);
    }
  }


  /**
   * @return stream of the script.
   */
  protected InputStream getScriptAsStream() {
    return getClass().getResourceAsStream("json.hpack.js");
  }


  public String unpack(final String rawData) {
    final StopWatch stopWatch = new StopWatch();
    stopWatch.start("initContext");
    final RhinoScriptBuilder builder = initScriptBuilder();
    stopWatch.stop();

    stopWatch.start("json.hunpack");

    final boolean isEnclosedInDoubleArray = isEnclosedInDoubleArray(rawData);
    String data = rawData;
    if (!isEnclosedInDoubleArray) {
      data = "[" + data + "]";
    }

    try {

      final String execute = "JSON.stringify(JsonHPack.hunpack(eval(" + WroUtil.toJSMultiLineString(data) + ")));";
      final Object result = builder.evaluate(execute, "unpack");

      String resultAsString = String.valueOf(result);
      if (!isEnclosedInDoubleArray) {
        //remove [] characters in which the json is enclosed
        resultAsString = removeEnclosedArray(resultAsString);
      }
      return resultAsString;
    } catch (final RhinoException e) {
      throw new WroRuntimeException(RhinoUtils.createExceptionMessage(e), e);
    } finally {
      stopWatch.stop();
      LOG.debug(stopWatch.prettyPrint());
    }
  }

  /**
   * @param data css content to process.
   * @return processed css content.
   */
  public String pack(final String rawData) {

    final StopWatch stopWatch = new StopWatch();
    stopWatch.start("initContext");
    final RhinoScriptBuilder builder = initScriptBuilder();
    stopWatch.stop();

    stopWatch.start("json.hpack");

    final boolean isEnclosedInArray = isEnclosedInArray(rawData);
    String data = rawData;
    if (!isEnclosedInArray) {
      data = "[" + data + "]";
    }

    try {
      final String execute = "JSON.stringify(JsonHPack.hpack(eval(" + WroUtil.toJSMultiLineString(data) + "), 4));";
      final Object result = builder.evaluate(execute, "pack");
      String resultAsString = String.valueOf(result);
      if (!isEnclosedInArray) {
        //remove [] characters in which the json is enclosed
        resultAsString = removeEnclosedArray(resultAsString);
      }
      return resultAsString;
    } catch (final RhinoException e) {
      throw new WroRuntimeException(RhinoUtils.createExceptionMessage(e), e);
    } finally {
      stopWatch.stop();
      LOG.debug(stopWatch.prettyPrint());
    }
  }


  /**
   * Remove first and last occurrence of '[' and ']' characters.
   * @param resultAsString
   * @return
   */
  private String removeEnclosedArray(String resultAsString) {
    resultAsString = resultAsString.replaceFirst("(?ims)\\[", "").replaceFirst("(?ims)\\](?!.*\\])", "");
    return resultAsString;
  }


  /**
   * Check if the string is enclosed with [] (array).
   * @param rawData string to test.
   */
  private boolean isEnclosedInArray(final String rawData) {
    return rawData.matches("(?ims)^\\s*\\[.*\\]");
  }

  /**
   * Check if the string is enclosed with [[]] (double array).
   * @param rawData string to test.
   */
  private boolean isEnclosedInDoubleArray(final String rawData) {
    return rawData.matches("(?ims)^\\s*\\[\\[.*\\]\\]");
  }
}
