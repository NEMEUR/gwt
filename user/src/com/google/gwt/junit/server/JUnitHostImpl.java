/*
 * Copyright 2008 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.junit.server;

import com.google.gwt.junit.JUnitFatalLaunchException;
import com.google.gwt.junit.JUnitMessageQueue;
import com.google.gwt.junit.JUnitShell;
import com.google.gwt.junit.client.TestResults;
import com.google.gwt.junit.client.Trial;
import com.google.gwt.junit.client.impl.ExceptionWrapper;
import com.google.gwt.junit.client.impl.JUnitHost;
import com.google.gwt.junit.client.impl.StackTraceWrapper;
import com.google.gwt.user.client.rpc.InvocationException;
import com.google.gwt.user.server.rpc.RPCServletUtils;
import com.google.gwt.user.server.rpc.RemoteServiceServlet;
import com.google.gwt.util.tools.Utility;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * An RPC servlet that serves as a proxy to JUnitTestShell. Enables
 * communication between the unit test code running in a browser and the real
 * test process.
 */
public class JUnitHostImpl extends RemoteServiceServlet implements JUnitHost {

  /**
   * A hook into GWTUnitTestShell, the underlying unit test process.
   */
  private static JUnitMessageQueue sHost = null;

  /**
   * A maximum timeout to wait for the test system to respond with the next
   * test. Practically speaking, the test system should respond nearly instantly
   * if there are further tests to run.
   */
  private static final int TIME_TO_WAIT_FOR_TESTNAME = 300000;

  /**
   * Tries to grab the GWTUnitTestShell sHost environment to communicate with
   * the real test process.
   */
  private static synchronized JUnitMessageQueue getHost() {
    if (sHost == null) {
      sHost = JUnitShell.getMessageQueue();
      if (sHost == null) {
        throw new InvocationException(
            "Unable to find JUnitShell; is this servlet running under GWTTestCase?");
      }
    }
    return sHost;
  }

  /**
   * Simple helper method to set inaccessible fields via reflection.
   */
  private static <T> void setField(Class<T> cls, String fieldName, T obj,
      Object value) throws SecurityException, NoSuchFieldException,
      IllegalArgumentException, IllegalAccessException {
    Field fld = cls.getDeclaredField(fieldName);
    fld.setAccessible(true);
    fld.set(obj, value);
  }

  public TestInfo getFirstMethod(String moduleName) {
    return getHost().getNextTestInfo(getClientId(), moduleName,
        TIME_TO_WAIT_FOR_TESTNAME);
  }

  public TestInfo reportResultsAndGetNextMethod(String moduleName,
      TestResults results) {
    JUnitMessageQueue host = getHost();
    HttpServletRequest request = getThreadLocalRequest();
    String agent = request.getHeader("User-Agent");
    results.setAgent(agent);
    String machine = request.getRemoteHost();
    results.setHost(machine);
    List<Trial> trials = results.getTrials();
    for (Trial trial : trials) {
      ExceptionWrapper ew = trial.getExceptionWrapper();
      trial.setException(deserialize(ew));
    }
    host.reportResults(moduleName, results);
    return host.getNextTestInfo(getClientId(), moduleName,
        TIME_TO_WAIT_FOR_TESTNAME);
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    String requestURI = request.getRequestURI();
    if (requestURI.endsWith("/junithost/junit.html")) {
      String prefix = getPrefix(requestURI);
      String moduleName = getModuleName(prefix);
      response.setContentType("text/html");
      PrintWriter writer = response.getWriter();
      String htmlSrc = Utility.getFileFromClassPath("com/google/gwt/junit/junit.html");
      htmlSrc = htmlSrc.replace("__MODULE_NAME__", prefix + "/" + moduleName);
      writer.write(htmlSrc);
      return;
    }
    response.setContentType("text/plain");
    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
  }

  @Override
  protected void service(HttpServletRequest request,
      HttpServletResponse response) throws ServletException, IOException {
    String requestURI = request.getRequestURI();
    if (requestURI.endsWith("/junithost/loadError")) {
      String moduleName = getModuleName(getPrefix(requestURI));
      String requestPayload = RPCServletUtils.readContentAsUtf8(request);
      TestResults results = new TestResults();
      Trial trial = new Trial();
      trial.setException(new JUnitFatalLaunchException(requestPayload));
      results.getTrials().add(trial);
      getHost().reportResults(moduleName, results);
    } else {
      super.service(request, response);
    }
  }

  /**
   * Deserializes an ExceptionWrapper back into a Throwable.
   */
  private Throwable deserialize(ExceptionWrapper ew) {
    if (ew == null) {
      return null;
    }

    Throwable ex = null;
    Throwable cause = deserialize(ew.cause);
    try {
      Class<?> exClass = Class.forName(ew.typeName);
      try {
        // try ExType(String, Throwable)
        Constructor<?> ctor = exClass.getDeclaredConstructor(String.class,
            Throwable.class);
        ctor.setAccessible(true);
        ex = (Throwable) ctor.newInstance(ew.message, cause);
      } catch (Throwable e) {
        // try ExType(String)
        try {
          Constructor<?> ctor = exClass.getDeclaredConstructor(String.class);
          ctor.setAccessible(true);
          ex = (Throwable) ctor.newInstance(ew.message);
          ex.initCause(cause);
        } catch (Throwable e2) {
          // try ExType(Throwable)
          try {
            Constructor<?> ctor = exClass.getDeclaredConstructor(Throwable.class);
            ctor.setAccessible(true);
            ex = (Throwable) ctor.newInstance(cause);
            setField(Throwable.class, "detailMessage", ex, ew.message);
          } catch (Throwable e3) {
            // try ExType()
            try {
              Constructor<?> ctor = exClass.getDeclaredConstructor();
              ctor.setAccessible(true);
              ex = (Throwable) ctor.newInstance();
              ex.initCause(cause);
              setField(Throwable.class, "detailMessage", ex, ew.message);
            } catch (Throwable e4) {
              // we're out of options
              this.log("Failed to deserialize getException of type '"
                  + ew.typeName + "'; no available constructor", e4);

              // fall through
            }
          }
        }
      }

    } catch (Throwable e) {
      this.log("Failed to deserialize getException of type '" + ew.typeName
          + "'", e);
    }

    if (ex == null) {
      ex = new RuntimeException(ew.typeName + ": " + ew.message, cause);
    }

    ex.setStackTrace(deserialize(ew.stackTrace));
    return ex;
  }

  /**
   * Deserializes a StackTraceWrapper back into a StackTraceElement.
   */
  private StackTraceElement deserialize(StackTraceWrapper stw) {
    StackTraceElement ste = null;
    Object[] args = new Object[] {
        stw.className, stw.methodName, stw.fileName, stw.lineNumber};
    try {
      try {
        // Try the 4-arg ctor (JRE 1.5)
        Constructor<StackTraceElement> ctor = StackTraceElement.class.getDeclaredConstructor(
            String.class, String.class, String.class, int.class);
        ctor.setAccessible(true);
        ste = ctor.newInstance(args);
      } catch (NoSuchMethodException e) {
        // Okay, see if there's a zero-arg ctor we can use instead (JRE 1.4.2)
        Constructor<StackTraceElement> ctor = StackTraceElement.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ste = ctor.newInstance();
        setField(StackTraceElement.class, "declaringClass", ste, args[0]);
        setField(StackTraceElement.class, "methodName", ste, args[1]);
        setField(StackTraceElement.class, "fileName", ste, args[2]);
        setField(StackTraceElement.class, "lineNumber", ste, args[3]);
      }
    } catch (Throwable e) {
      this.log("Error creating stack trace", e);
    }
    return ste;
  }

  /**
   * Deserializes a StackTraceWrapper[] back into a StackTraceElement[].
   */
  private StackTraceElement[] deserialize(StackTraceWrapper[] stackTrace) {
    int len = stackTrace.length;
    StackTraceElement[] result = new StackTraceElement[len];
    for (int i = 0; i < len; ++i) {
      result[i] = deserialize(stackTrace[i]);
    }
    return result;
  }

  /**
   * Returns a "client id" for the current request.
   */
  private String getClientId() {
    HttpServletRequest request = getThreadLocalRequest();
    String agent = request.getHeader("User-Agent");
    String machine = request.getRemoteHost();
    return machine + " / " + agent;
  }

  private String getModuleName(String prefix) {
    int pos = prefix.lastIndexOf('/');
    String moduleName = prefix.substring(pos + 1);
    return moduleName;
  }

  private String getPrefix(String requestURI) {
    int pos = requestURI.indexOf("/junithost");
    String prefix = requestURI.substring(0, pos);
    return prefix;
  }
}
