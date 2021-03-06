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

package io.zeebe.model.bpmn.impl.instance;

import static io.zeebe.model.bpmn.impl.BpmnModelConstants.BPMN20_NS;
import static io.zeebe.model.bpmn.impl.BpmnModelConstants.BPMN_ATTRIBUTE_NAME;
import static io.zeebe.model.bpmn.impl.BpmnModelConstants.BPMN_ELEMENT_CORRELATION_KEY;

import io.zeebe.model.bpmn.instance.BaseElement;
import io.zeebe.model.bpmn.instance.CorrelationKey;
import io.zeebe.model.bpmn.instance.CorrelationProperty;
import java.util.Collection;
import org.camunda.bpm.model.xml.ModelBuilder;
import org.camunda.bpm.model.xml.impl.instance.ModelTypeInstanceContext;
import org.camunda.bpm.model.xml.type.ModelElementTypeBuilder;
import org.camunda.bpm.model.xml.type.ModelElementTypeBuilder.ModelTypeInstanceProvider;
import org.camunda.bpm.model.xml.type.attribute.Attribute;
import org.camunda.bpm.model.xml.type.child.SequenceBuilder;
import org.camunda.bpm.model.xml.type.reference.ElementReferenceCollection;

/**
 * The BPMN correlationKey element
 *
 * @author Sebastian Menski
 */
public class CorrelationKeyImpl extends BaseElementImpl implements CorrelationKey {

  protected static Attribute<String> nameAttribute;
  protected static ElementReferenceCollection<CorrelationProperty, CorrelationPropertyRef>
      correlationPropertyRefCollection;

  public CorrelationKeyImpl(ModelTypeInstanceContext instanceContext) {
    super(instanceContext);
  }

  public static void registerType(ModelBuilder modelBuilder) {
    final ModelElementTypeBuilder typeBuilder =
        modelBuilder
            .defineType(CorrelationKey.class, BPMN_ELEMENT_CORRELATION_KEY)
            .namespaceUri(BPMN20_NS)
            .extendsType(BaseElement.class)
            .instanceProvider(
                new ModelTypeInstanceProvider<CorrelationKey>() {
                  @Override
                  public CorrelationKey newInstance(ModelTypeInstanceContext instanceContext) {
                    return new CorrelationKeyImpl(instanceContext);
                  }
                });

    nameAttribute = typeBuilder.stringAttribute(BPMN_ATTRIBUTE_NAME).build();

    final SequenceBuilder sequenceBuilder = typeBuilder.sequence();

    correlationPropertyRefCollection =
        sequenceBuilder
            .elementCollection(CorrelationPropertyRef.class)
            .qNameElementReferenceCollection(CorrelationProperty.class)
            .build();

    typeBuilder.build();
  }

  @Override
  public String getName() {
    return nameAttribute.getValue(this);
  }

  @Override
  public void setName(String name) {
    nameAttribute.setValue(this, name);
  }

  @Override
  public Collection<CorrelationProperty> getCorrelationProperties() {
    return correlationPropertyRefCollection.getReferenceTargetElements(this);
  }
}
