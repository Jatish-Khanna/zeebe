/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.model.bpmn.instance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;

public class ConditionalEventDefinitionTest extends AbstractEventDefinitionTest {

  @Override
  public Collection<ChildElementAssumption> getChildElementAssumptions() {
    return Arrays.asList(new ChildElementAssumption(Condition.class, 1, 1));
  }

  @Override
  public Collection<AttributeAssumption> getAttributesAssumptions() {
    return null;
  }

  @Test
  public void getEventDefinition() {
    final ConditionalEventDefinition eventDefinition =
        eventDefinitionQuery.filterByType(ConditionalEventDefinition.class).singleResult();
    assertThat(eventDefinition).isNotNull();
    final Expression condition = eventDefinition.getCondition();
    assertThat(condition).isNotNull();
    assertThat(condition.getTextContent()).isEqualTo("${test}");
  }
}
