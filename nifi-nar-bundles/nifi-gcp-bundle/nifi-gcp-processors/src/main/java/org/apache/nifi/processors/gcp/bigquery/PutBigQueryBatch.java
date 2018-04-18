/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.processors.gcp.bigquery;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FormatOptions;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.TableDataWriteChannel;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.WriteChannelConfiguration;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.LogLevel;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.gcp.storage.DeleteGCSObject;
import org.apache.nifi.processors.gcp.storage.PutGCSObject;

/**
 * A processor for batch loading data into a Google BigQuery table
 */

@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"google", "google cloud", "bq", "bigquery"})
@CapabilityDescription("Batch loads flow files to a Google BigQuery table.")
@SeeAlso({PutGCSObject.class, DeleteGCSObject.class, PutBigQueryStream.class})

@WritesAttributes({
    @WritesAttribute(attribute = BigQueryAttributes.DATASET_ATTR, description = BigQueryAttributes.DATASET_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.TABLE_NAME_ATTR, description = BigQueryAttributes.TABLE_NAME_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.TABLE_SCHEMA_ATTR, description = BigQueryAttributes.TABLE_SCHEMA_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.SOURCE_TYPE_ATTR, description = BigQueryAttributes.SOURCE_TYPE_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.IGNORE_UNKNOWN_ATTR, description = BigQueryAttributes.IGNORE_UNKNOWN_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.CREATE_DISPOSITION_ATTR, description = BigQueryAttributes.CREATE_DISPOSITION_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.WRITE_DISPOSITION_ATTR, description = BigQueryAttributes.WRITE_DISPOSITION_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.MAX_BADRECORDS_ATTR, description = BigQueryAttributes.MAX_BADRECORDS_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.JOB_CREATE_TIME_ATTR, description = BigQueryAttributes.JOB_CREATE_TIME_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.JOB_END_TIME_ATTR, description = BigQueryAttributes.JOB_END_TIME_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.JOB_START_TIME_ATTR, description = BigQueryAttributes.JOB_START_TIME_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.JOB_LINK_ATTR, description = BigQueryAttributes.JOB_LINK_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.JOB_ERROR_MSG_ATTR, description = BigQueryAttributes.JOB_ERROR_MSG_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.JOB_ERROR_REASON_ATTR, description = BigQueryAttributes.JOB_ERROR_REASON_DESC),
    @WritesAttribute(attribute = BigQueryAttributes.JOB_ERROR_LOCATION_ATTR, description = BigQueryAttributes.JOB_ERROR_LOCATION_DESC)
})

public class PutBigQueryBatch extends AbstractBigQueryProcessor {

    public static final PropertyDescriptor DATASET = new PropertyDescriptor
        .Builder().name(BigQueryAttributes.DATASET_ATTR)
        .displayName("Dataset")
        .description(BigQueryAttributes.DATASET_DESC)
        .required(true)
        .defaultValue("${" + BigQueryAttributes.DATASET_ATTR + "}")
        .expressionLanguageSupported(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    public static final PropertyDescriptor TABLE_NAME = new PropertyDescriptor
        .Builder().name(BigQueryAttributes.TABLE_NAME_ATTR)
        .displayName("Table Name")
        .description(BigQueryAttributes.TABLE_NAME_DESC)
        .required(true)
        .defaultValue("${" + BigQueryAttributes.TABLE_NAME_ATTR + "}")
        .expressionLanguageSupported(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    public static final PropertyDescriptor TABLE_SCHEMA = new PropertyDescriptor
        .Builder().name(BigQueryAttributes.TABLE_SCHEMA_ATTR)
        .displayName("Table Schema")
        .description(BigQueryAttributes.TABLE_SCHEMA_DESC)
        .required(false)
        .expressionLanguageSupported(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    public static final PropertyDescriptor SOURCE_TYPE = new PropertyDescriptor
        .Builder().name(BigQueryAttributes.SOURCE_TYPE_ATTR)
        .displayName("Load file type")
        .description(BigQueryAttributes.SOURCE_TYPE_DESC)
        .required(true)
        .allowableValues(FormatOptions.json().getType(), FormatOptions.avro().getType(), FormatOptions.csv().getType())
        .defaultValue(FormatOptions.avro().getType())
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    public static final PropertyDescriptor IGNORE_UNKNOWN = new PropertyDescriptor.Builder()
        .name(BigQueryAttributes.IGNORE_UNKNOWN_ATTR)
        .displayName("Ignore Unknown Values")
        .description(BigQueryAttributes.IGNORE_UNKNOWN_DESC)
        .required(true)
        .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
        .allowableValues("true", "false")
        .defaultValue("true")
        .build();

    public static final PropertyDescriptor CREATE_DISPOSITION = new PropertyDescriptor.Builder()
        .name(BigQueryAttributes.CREATE_DISPOSITION_ATTR)
        .displayName("Create Disposition")
        .description(BigQueryAttributes.CREATE_DISPOSITION_DESC)
        .required(true)
        .allowableValues(JobInfo.CreateDisposition.CREATE_IF_NEEDED.name(), JobInfo.CreateDisposition.CREATE_NEVER.name())
        .defaultValue(JobInfo.CreateDisposition.CREATE_IF_NEEDED.name())
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    public static final PropertyDescriptor WRITE_DISPOSITION = new PropertyDescriptor.Builder()
        .name(BigQueryAttributes.WRITE_DISPOSITION_ATTR)
        .displayName("Write Disposition")
        .description(BigQueryAttributes.WRITE_DISPOSITION_DESC)
        .required(true)
        .allowableValues(JobInfo.WriteDisposition.WRITE_EMPTY.name(), JobInfo.WriteDisposition.WRITE_APPEND.name(), JobInfo.WriteDisposition.WRITE_TRUNCATE.name())
        .defaultValue(JobInfo.WriteDisposition.WRITE_EMPTY.name())
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    public static final PropertyDescriptor MAXBAD_RECORDS = new PropertyDescriptor.Builder()
        .name(BigQueryAttributes.MAX_BADRECORDS_ATTR)
        .displayName("Max Bad Records")
        .description(BigQueryAttributes.MAX_BADRECORDS_DESC)
        .required(true)
        .defaultValue("0")
        .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
        .build();

    private Schema schemaCache = null;

    public PutBigQueryBatch() {

    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return ImmutableList.<PropertyDescriptor>builder()
                .addAll(super.getSupportedPropertyDescriptors())
                .add(DATASET)
                .add(TABLE_NAME)
                .add(TABLE_SCHEMA)
                .add(SOURCE_TYPE)
                .add(CREATE_DISPOSITION)
                .add(WRITE_DISPOSITION)
                .add(MAXBAD_RECORDS)
                .add(IGNORE_UNKNOWN)
                .build();
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .expressionLanguageSupported(true)
                .dynamic(true)
                .build();
    }

    @Override
    public void onScheduled(ProcessContext context) {
        super.onScheduled(context);

        if(schemaCache == null) {
            String schemaStr = context.getProperty(TABLE_SCHEMA).getValue();
            schemaCache = BqUtils.schemaFromString(schemaStr);
        }
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile flow = session.get();
        if (flow == null) {
            return;
        }

        final Map<String, String> attributes = new HashMap<>();

        final BigQuery bq = getCloudService();

        final String projectId = context.getProperty(PROJECT_ID).evaluateAttributeExpressions().getValue();
        final String dataset_str = context.getProperty(DATASET).evaluateAttributeExpressions(flow).getValue();
        final String tablename_str = context.getProperty(TABLE_NAME).evaluateAttributeExpressions(flow).getValue();
        final TableId tableId = TableId.of(projectId, dataset_str, tablename_str);

        final String fileType = context.getProperty(SOURCE_TYPE).getValue();

        WriteChannelConfiguration writeChannelConfiguration =
                WriteChannelConfiguration.newBuilder(tableId)
                        .setCreateDisposition(JobInfo.CreateDisposition.valueOf(context.getProperty(CREATE_DISPOSITION).getValue()))
                        .setWriteDisposition(JobInfo.WriteDisposition.valueOf(context.getProperty(WRITE_DISPOSITION).getValue()))
                        .setIgnoreUnknownValues(context.getProperty(IGNORE_UNKNOWN).asBoolean())
                        .setMaxBadRecords(context.getProperty(MAXBAD_RECORDS).asInteger())
                        .setSchema(schemaCache)
                        .setFormatOptions(FormatOptions.of(fileType))
                        .build();
        TableDataWriteChannel writer = bq.writer(writeChannelConfiguration);

        try {
            try (InputStream read = session.read(flow)) {
                byte[] bytes = IOUtils.toByteArray(read);
                ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
                writer.write(byteBuffer);
            } finally {
                writer.close();
            }
        } catch (Throwable ex) {
            getLogger().log(LogLevel.ERROR, ex.getMessage(), ex);
            flow = session.penalize(flow);
            session.transfer(flow, REL_FAILURE);
            return;
        }

        Job job = writer.getJob();

        try {
            job = job.waitFor();
        } catch (Throwable ex) {
            getLogger().log(LogLevel.ERROR, ex.getMessage(), ex);
            flow = session.penalize(flow);
            session.transfer(flow, REL_FAILURE);
            return;
        }

        boolean jobError = (job.getStatus().getError() != null);

        attributes.put(BigQueryAttributes.JOB_CREATE_TIME_ATTR, Long.toString(job.getStatistics().getCreationTime()));
        attributes.put(BigQueryAttributes.JOB_END_TIME_ATTR, Long.toString(job.getStatistics().getEndTime()));
        attributes.put(BigQueryAttributes.JOB_START_TIME_ATTR, Long.toString(job.getStatistics().getStartTime()));

        attributes.put(BigQueryAttributes.JOB_LINK_ATTR, job.getSelfLink());

        if(jobError) {
            attributes.put(BigQueryAttributes.JOB_ERROR_MSG_ATTR, job.getStatus().getError().getMessage());
            attributes.put(BigQueryAttributes.JOB_ERROR_REASON_ATTR, job.getStatus().getError().getReason());
            attributes.put(BigQueryAttributes.JOB_ERROR_LOCATION_ATTR, job.getStatus().getError().getLocation());
        } else {
            // in case it got looped back from error
            flow = session.removeAttribute(flow, BigQueryAttributes.JOB_ERROR_MSG_ATTR);
            flow = session.removeAttribute(flow, BigQueryAttributes.JOB_ERROR_REASON_ATTR);
            flow = session.removeAttribute(flow, BigQueryAttributes.JOB_ERROR_LOCATION_ATTR);
        }

        if (!attributes.isEmpty()) {
            flow = session.putAllAttributes(flow, attributes);
        }

        if(jobError) {
            flow = session.penalize(flow);
            session.transfer(flow, REL_FAILURE);
        } else {
            session.transfer(flow, REL_SUCCESS);
        }
    }
}
