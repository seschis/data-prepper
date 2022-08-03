/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.plugins.processor.csv;

import com.amazon.dataprepper.metrics.PluginMetrics;
import com.amazon.dataprepper.model.annotations.DataPrepperPlugin;
import com.amazon.dataprepper.model.annotations.DataPrepperPluginConstructor;
import com.amazon.dataprepper.model.event.Event;
import com.amazon.dataprepper.model.processor.AbstractProcessor;
import com.amazon.dataprepper.model.processor.Processor;
import com.amazon.dataprepper.model.record.Record;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Processor to parse CSV data in Events.
 *
 */
@DataPrepperPlugin(name="csv", pluginType = Processor.class, pluginConfigurationType = CSVProcessorConfig.class)
public class CSVProcessor extends AbstractProcessor<Record<Event>, Record<Event>> {
    private static final Logger LOG = LoggerFactory.getLogger(CSVProcessor.class);
    private final CSVProcessorConfig config;

    @DataPrepperPluginConstructor
    public CSVProcessor(final PluginMetrics pluginMetrics, final CSVProcessorConfig config) {
        super(pluginMetrics);
        this.config = config;
    }

    @Override
    public Collection<Record<Event>> doExecute(final Collection<Record<Event>> records) {
        final CsvMapper mapper = createCsvMapper();
        final CsvSchema schema = createCsvSchema();

        for (final Record<Event> record : records) {

            final Event event = record.getData();

            final String message = event.get(config.getSource(), String.class);
            final boolean userDidSpecifyHeaderEventKey = Objects.nonNull(config.getColumnNamesSourceKey());
            final boolean thisEventHasHeaderSource = userDidSpecifyHeaderEventKey && event.containsKey(config.getColumnNamesSourceKey());

            try {
                final MappingIterator<List<String>> messageIterator = mapper.readerFor(List.class).with(schema).readValues(message);

                // otherwise the message is empty
                if (messageIterator.hasNextValue()) {
                    final List<String> row = messageIterator.nextValue();
                    final List<String> header = parseHeader(event, thisEventHasHeaderSource, mapper, schema);
                    putDataInEvent(event, header, row);
                }

                if (thisEventHasHeaderSource && Boolean.TRUE.equals(config.isDeleteHeader())) {
                    event.delete(config.getColumnNamesSourceKey());
                }
            } catch (final IOException e) {
                LOG.error("An exception occurred while reading event [{}]", event, e);
            }
        }
        return records;
    }

    @Override
    public void prepareForShutdown() {

    }

    @Override
    public boolean isReadyForShutdown() {
        return true;
    }

    @Override
    public void shutdown() {

    }

    private CsvMapper createCsvMapper() {
        final CsvMapper mapper = new CsvMapper();
        mapper.enable(CsvParser.Feature.WRAP_AS_ARRAY); // allows mapper to read with empty schema
        return mapper;
    }

    private CsvSchema createCsvSchema() {
        final char delimiterAsChar = config.getDelimiter().charAt(0); // safe due to config input validations
        final char quoteCharAsChar = config.getQuoteCharacter().charAt(0); // safe due to config input validations
        final CsvSchema schema = CsvSchema.emptySchema().withColumnSeparator(delimiterAsChar).withQuoteChar(quoteCharAsChar);
        return schema;
    }

    private List<String> parseHeader(final Event event, final boolean thisEventHasHeaderSource, final CsvMapper mapper,
                                     final CsvSchema schema) {
        if (thisEventHasHeaderSource) {
            return parseHeaderFromEventSourceKey(event, mapper, schema);
        }
        else if (Objects.nonNull(config.getColumnNames())) {
            return config.getColumnNames();
        }
        else {
            final List<String> emptyHeader = new ArrayList<>();
            return emptyHeader;
        }
    }

    private List<String> parseHeaderFromEventSourceKey(final Event event, final CsvMapper mapper, final CsvSchema schema) {
        try {
            final String headerUnprocessed = event.get(config.getColumnNamesSourceKey(), String.class);
            final MappingIterator<List<String>> headerIterator = mapper.readerFor(List.class).with(schema)
                    .readValues(headerUnprocessed);
            // if header is empty, behaves correctly since columns are autogenerated.
            final List<String> headerFromEventSource = headerIterator.nextValue();
            return headerFromEventSource;
        }
        catch (final IOException e) {
            LOG.debug("Auto generating header because of IOException on the header of event [{}]", event, e);
            final List<String> emptyHeader = new ArrayList<>();
            return emptyHeader;
        }
    }

    private void putDataInEvent(final Event event, final List<String> header, final List<String> data) {
        int providedHeaderColIdx = 0;
        for (; providedHeaderColIdx < header.size() && providedHeaderColIdx < data.size(); providedHeaderColIdx++) {
            event.put(header.get(providedHeaderColIdx), data.get(providedHeaderColIdx));
        }
        for (int remainingColIdx = providedHeaderColIdx; remainingColIdx < data.size(); remainingColIdx++) {
            event.put(generateColumnHeader(remainingColIdx), data.get(remainingColIdx));
        }
    }

    private String generateColumnHeader(final int colNumber) {
        final int displayColNumber = colNumber + 1; // auto generated column name indices start from 1 (not 0)
        return "column" + displayColNumber;
    }
}
