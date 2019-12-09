/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.bootstrap;

public class StartStep {

  private final String name;
  private final StartFunction startFunction;

  public StartStep(String name, StartFunction startFunction) {
    this.name = name;
    this.startFunction = startFunction;
  }

  public String getName() {
    return name;
  }

  public StartFunction getStartFunction() {
    return startFunction;
  }
}
