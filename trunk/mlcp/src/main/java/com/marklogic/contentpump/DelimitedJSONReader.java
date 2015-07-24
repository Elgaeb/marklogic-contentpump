/*
 * Copyright 2003-2015 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.contentpump;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.ObjectMapper;

import com.marklogic.contentpump.utilities.FileIterator;
import com.marklogic.contentpump.utilities.IdGenerator;

/**
 * Reader for DelimitedJSONInputFormat.
 * @author mattsun
 *
 * @param <VALUEIN>
 */
public class DelimitedJSONReader<VALUEIN> extends 
    ImportRecordReader<VALUEIN> {
    /* Log */
    public static final Log LOG = LogFactory.getLog(DelimitedJSONReader.class);
    /* Input Handling */
    protected InputStreamReader instream;
    protected FSDataInputStream fileIn;
    // LineNumberReader inherits from BufferedReader. Can get current line number
    protected LineNumberReader reader;
    /* JSON Parser */
    protected ObjectMapper mapper;
    /* Reader Property */
    protected String uriName;
    protected long totalBytes = Long.MAX_VALUE;
    protected long bytesRead;
    protected boolean generateId = true;
    protected IdGenerator idGen;
    protected boolean hasNext = true;
    
    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
        if (instream != null) {
            instream.close();
        }
        
    }

    @Override
    public float getProgress() throws IOException, InterruptedException {
        return bytesRead/(float)totalBytes;
    }

    @Override
    public void initialize(InputSplit inSplit, TaskAttemptContext context)
            throws IOException, InterruptedException {
        /* Initialization in super class */
        initConfig(context);  
        /*  Get file(s) in input split */
        file = ((FileSplit) inSplit).getPath();
        // Initialize reader properties
        generateId = conf.getBoolean(CONF_DELIMITED_JSON_GENERATE_URI ,false);
        if (generateId){
            idGen = new IdGenerator(file.toUri().getPath() + "-"
                    + ((FileSplit) inSplit).getStart()); 
        } else {
            uriName = conf.get(CONF_DELIMITED_JSON_URI_ID, null);
            mapper = new ObjectMapper();
        }
        bytesRead = 0;
        totalBytes = inSplit.getLength();
        /* Check file status */
        fs = file.getFileSystem(context.getConfiguration());
        FileStatus status = fs.getFileStatus(file);
        if (status.isDirectory()) {
            iterator = new FileIterator((FileSplit)inSplit, context);
            inSplit = iterator.next();
        }
        /* Initialize buffered reader */
        initFileStream(inSplit);
    }

    protected boolean findNextFileEntryAndInitReader() throws InterruptedException, IOException {
        if (iterator != null && iterator.hasNext()) {
            close();
            initFileStream(iterator.next());
            return true;
        } else {
            hasNext = false;
            return false;
        }

    }
    
    protected void initFileStream(InputSplit inSplit) 
            throws IOException, InterruptedException {
        file = ((FileSplit) inSplit).getPath();
        configFileNameAsCollection(conf, file);     
        fileIn = fs.open(file);
        instream = new InputStreamReader(fileIn, encoding);
        reader = new LineNumberReader(instream);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public boolean nextKeyValue() throws IOException, InterruptedException {
        if (reader == null) {
            hasNext = false;
            return false;
        }
        String line = reader.readLine();
        if (line == null) {
            if (findNextFileEntryAndInitReader()) {
                return nextKeyValue();
            } else { // End of the directory
                bytesRead = totalBytes;
                return false;
            }
        } else if (line.trim().equals("")){ // blank lines or lines containing only spaces
            LOG.warn("File " + file.toUri() + ""
                    + " line " + reader.getLineNumber() + " skipped: no document ");
            setKey(null);
            return true;
        } else if (line.startsWith(" ")) { // lines with trailing spaces considered invalid
            LOG.error("File " + file.toUri() + 
                        " line " + reader.getLineNumber() + 
                            " skipped: starts with spaces");
            setKey(null);
            return true;
        } else {
            if (generateId) {
                setKey(idGen.incrementAndGet());
            } else {
                String uri = null;
                try {
                    uri = findUriInJSON(line.trim());
                } catch (JsonParseException ex) {
                    LOG.error("File " + file.toUri() + " line "
                            + reader.getLineNumber()
                                    + " skipped: not valid JSON document");
                }
                setKey(uri);
                if (uri == null) {
                    return true;
                }
            }
            
            if (value instanceof Text) {
                ((Text) value).set(line);
            } else if (value instanceof ContentWithFileNameWritable) {
                VALUEIN realValue = ((ContentWithFileNameWritable<VALUEIN>) value)
                        .getValue();
                if (realValue instanceof Text) {
                    ((Text) realValue).set(line);
                } else {
                    LOG.error("Expects Text in delimited JSON");
                    setKey(null);
                }
            } else {
                LOG.error("Expects Text in delimited JSON");
                setKey(null);
            }
        }
        bytesRead += (long)line.getBytes().length;
        return true;
        
    }
    
    @Override
    protected void setKey(String val) {
        if (val == null) {
            key = null;
        } else {
            String uri = getEncodedURI(val);
            super.setKey(uri);
        }
    }
    
    @SuppressWarnings("unchecked")
    protected String findUriInJSON(String line) throws JsonParseException, IOException {
        /* Breadth-First-Search */
        Map<String,?> root = mapper.readValue(line.getBytes(),Map.class);
        
        Queue<Map<String,?>> q = new LinkedList<Map<String,?>>();
        q.add(root);
        
        while (!q.isEmpty()) {
            Map<String,?> current = q.remove();
            // First Match
            if (current.containsKey(uriName)) {
                Object uriValue = current.get(uriName);
                if (uriValue instanceof Number || uriValue instanceof String) {
                    return uriValue.toString();
                } else {
                    LOG.error("File " + file.toUri() + " line "
                            + reader.getLineNumber() + " skipped: uri_id expects string or number");
                    return null;
                }
            }
            // Add child elements to queue
            Iterator<?> it = current.entrySet().iterator();
            while (it.hasNext()) {
                Entry<String,?> KVpair = (Entry<String,?>)it.next();
                Object pairValue = KVpair.getValue();
                
                if (pairValue instanceof Map) q.add((Map<String,?>)pairValue);
                else if (pairValue instanceof ArrayList) q.addAll((ArrayList<Map<String,?>>)pairValue);
            };
        }
        LOG.error("File " + file.toUri() + " line "
                      + reader.getLineNumber()
                              + " skipped: no uri " + uriName + " found");
        return null;
    }
    
}
