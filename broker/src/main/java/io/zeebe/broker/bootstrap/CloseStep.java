/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.bootstrap;

public class CloseStep {

  private final String name;
  private final AutoCloseable closingFunction;

  public CloseStep(String name, AutoCloseable closingFunction) {
    this.name = name;
    this.closingFunction = closingFunction;
  }

  public String getName() {
    return name;
  }

  public AutoCloseable getClosingFunction() {
    return closingFunction;
  }
}
