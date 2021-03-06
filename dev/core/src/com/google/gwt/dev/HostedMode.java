/*
 * Copyright 2009 Google Inc.
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
package com.google.gwt.dev;

import com.google.gwt.core.ext.TreeLogger;

/**
 * Support old name for this entry point, logging a warning message before
 * redirecting to the new name.
 */
@Deprecated
public class HostedMode extends DevMode {

  /**
   * Support old name for this entry point, logging a warning message before
   * redirecting to the new name.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    HostedMode hostedMode = new HostedMode();
    if (new ArgProcessor(hostedMode.options).processArgs(args)) {
      hostedMode.run();
      // Exit w/ success code.
      System.exit(0);
    }
    // Exit w/ non-success code.
    System.exit(-1);
  }

  @Override
  protected boolean doStartup() {
    if (!super.doStartup()) {
      return false;
    }
    getTopLogger().log(TreeLogger.WARN, "The class "
        + HostedMode.class.getName()
        + " is deprecated and will be removed -- use "
        + DevMode.class.getName() + " instead");
    return true;
  }
}
