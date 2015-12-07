/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.reporting.atlas;

import org.apache.atlas.AtlasClient;
import org.apache.atlas.nifi.model.NiFiDataModelGenerator;
import org.apache.atlas.nifi.model.NiFiDataTypes;
import org.apache.atlas.typesystem.Referenceable;
import org.apache.nifi.action.Action;
import org.apache.nifi.action.Component;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.AbstractReportingTask;
import org.apache.nifi.reporting.EventAccess;
import org.apache.nifi.reporting.ReportingContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Tags({"reporting", "atlas", "lineage", "governance"})
@CapabilityDescription("Publishes flow changes and metadata to Apache Atlas")
public class AtlasReportingTask extends AbstractReportingTask {

    static final PropertyDescriptor ATLAS_URL = new PropertyDescriptor.Builder()
            .name("Atlas URL")
            .description("The URL of the Atlas Server")
            .required(true)
            .expressionLanguageSupported(true)
            .defaultValue("http://localhost:21000/")
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();
    static final PropertyDescriptor ACTION_PAGE_SIZE = new PropertyDescriptor.Builder()
            .name("Action Page Size")
            .description("The size of each page to use when paging through the NiFi actions list.")
            .required(true)
            .defaultValue("100")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    private int lastId = 0; // TODO store on disk, this is for demo only
    private AtlasClient atlasClient;
    private AtomicReference<Referenceable> flowControllerRef = new AtomicReference<>();
    private AtomicReference<Referenceable> rootGroupRef = new AtomicReference<>();

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(ATLAS_URL);
        properties.add(ACTION_PAGE_SIZE);
        return properties;
    }

    @Override
    public void onTrigger(ReportingContext reportingContext) {
        // create the Atlas client if we don't have one
        if (atlasClient == null) {
            final String atlasUrl = reportingContext.getProperty(ATLAS_URL).getValue();
            getLogger().debug("Creating new Atlas client for {}", new Object[] {atlasUrl});
            atlasClient = new AtlasClient(atlasUrl);
        }

        // load the reference to the flow controller, if it doesn't exist then create it
       if (flowControllerRef.get() == null) {
            try {
                Referenceable flowController = getFlowControllerReference(reportingContext);
                if (flowController == null) {
                    getLogger().debug("flow controller didn't exist, creating it...");
                    flowController = createFlowController(reportingContext);
                    flowController = ReferenceableUtil.register(atlasClient, flowController);
                }
                flowControllerRef.set(flowController);
            } catch (Exception e) {
                getLogger().error("Unable to get reference to flow controller from Atlas", e);
                throw new ProcessException(e);
            }
        } else {
           getLogger().debug("Already have reference to flow controller, nothing to do...");
       }

        // load the reference to the root process group, if it doesn't exist then create it
        if (rootGroupRef.get() == null) {
            try {
                Referenceable rootGroup = getRootGroupReference(reportingContext);
                if (rootGroup == null) {
                    getLogger().debug("root process group didn't exist, creating it...");
                    rootGroup = createRootProcessGroup(reportingContext, flowControllerRef.get());
                    rootGroup = ReferenceableUtil.register(atlasClient, rootGroup);
                }
                rootGroupRef.set(rootGroup);
            } catch (Exception e) {
                getLogger().error("Unable to get reference to root group from Atlas", e);
                throw new ProcessException(e);
            }
        } else {
            getLogger().debug("Already have reference to root group, nothing to do...");
        }

        final EventAccess eventAccess = reportingContext.getEventAccess();
        final int pageSize = reportingContext.getProperty(ACTION_PAGE_SIZE).asInteger();

        // grab new actions starting from lastId up to pageSize
        List<Action> actions = eventAccess.getFlowChanges(lastId, pageSize);
        if (actions == null || actions.size() == 0) {
            getLogger().debug("No actions to process since last execution, lastId = {}", new Object[] {lastId});
        }

        // page through actions and process each page
        while (actions != null && actions.size() > 0) {
            for (final Action action : actions) {
                try {
                    // TODO eventually send multiple actions in a single request
                    processAction(action);
                } catch (Exception e) {
                    getLogger().error("Unable to process Action", e);
                    throw new ProcessException(e);
                }
                lastId = action.getId();
            }
            actions = eventAccess.getFlowChanges(lastId + 1, pageSize);
        }

        getLogger().debug("Done processing actions");
    }

    private void processAction(Action action) throws Exception {
        getLogger().debug("Processing action with id {}", new Object[] {action.getId()});
        switch (action.getOperation()) {
            case Add:
                if (action.getSourceType().equals(Component.Processor)) {
                    ReferenceableUtil.register(atlasClient, createProcessor(action));
                }
                break;
            case Connect:
                break;
            default:
                break;
        }
    }

    private Referenceable getFlowControllerReference(final ReportingContext context) throws Exception {
        final String typeName = NiFiDataTypes.NIFI_FLOW_CONTROLLER.getName();
        final String id = context.getEventAccess().getControllerStatus().getId();

        String dslQuery = String.format("%s where %s = '%s'", typeName, AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, id);
        return ReferenceableUtil.getEntityReferenceFromDSL(atlasClient, typeName, dslQuery);
    }

    private Referenceable getRootGroupReference(final ReportingContext context) throws Exception {
        final String typeName = NiFiDataTypes.NIFI_PROCESS_GROUP.getName();
        final String id = context.getEventAccess().getControllerStatus().getId();

        String dslQuery = String.format("%s where %s = '%s'", typeName, AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, id);
        return ReferenceableUtil.getEntityReferenceFromDSL(atlasClient, typeName, dslQuery);
    }

    private Referenceable createFlowController(final ReportingContext context) {
        final String id = context.getEventAccess().getControllerStatus().getId();
        final Referenceable flowController = new Referenceable(NiFiDataTypes.NIFI_FLOW_CONTROLLER.getName());
        flowController.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, id);
        return flowController;
    }

    private Referenceable createRootProcessGroup(final ReportingContext context, final Referenceable flowController) {
        final String id = context.getEventAccess().getControllerStatus().getId();
        final String name = context.getEventAccess().getControllerStatus().getName();

        final Referenceable rootGroup = new Referenceable(NiFiDataTypes.NIFI_PROCESS_GROUP.getName());
        rootGroup.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, id);
        rootGroup.set(NiFiDataModelGenerator.NAME, name);
        rootGroup.set(NiFiDataModelGenerator.FLOW, flowController);
        return rootGroup;
    }

    private Referenceable createProcessor(final Action action) {
        final String id = action.getSourceId();
        final String name = action.getSourceName();

        // TODO populate processor properties and determine real parent group, assuming root group for now
        final Referenceable processor = new Referenceable(NiFiDataTypes.NIFI_PROCESSOR.getName());
        processor.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, id);
        processor.set(NiFiDataModelGenerator.NAME, name);
        processor.set(NiFiDataModelGenerator.PROCESS_GROUP, rootGroupRef.get());
        return processor;
    }

}
