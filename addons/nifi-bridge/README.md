# Quick Start

## Creating the NiFi data model in Atlas
* Start Atlas – bin/atlas_start.py
* Run NiFiDataModelGenerator and copy the JSON for the data model
* POST the JSON to http://localhost:21000/api/atlas/types
* Verify the NiFi types got added by performing a GET request to http://localhost:21000/api/atlas/types

## Deploy the Atlas ReportingTask to NiFi
* mvn clean package of nifi-bridge
* cp nifi-atlas-bundle/nifi-atlas-nar/target/nifi-atlas-nar-0.6-incubating-SNAPSHOT.nar to NIFI_HOME/lib
* Start NiFi and add the AtlasReportingTask
* Verify the Flow Controller was created in Atlas - http://localhost:21000/api/atlas/entities?type=nifi_flow_controller
