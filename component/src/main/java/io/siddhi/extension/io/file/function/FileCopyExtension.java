/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.siddhi.extension.io.file.function;

import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.ReturnAttribute;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.exception.SiddhiAppRuntimeException;
import io.siddhi.core.executor.ExpressionExecutor;
import io.siddhi.core.executor.function.FunctionExecutor;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.snapshot.state.State;
import io.siddhi.core.util.snapshot.state.StateFactory;
import io.siddhi.extension.io.file.util.Constants;
import io.siddhi.extension.io.file.util.VFSClientConnectorCallback;
import io.siddhi.query.api.definition.Attribute;
import io.siddhi.query.api.exception.SiddhiAppValidationException;
import org.apache.commons.io.FilenameUtils;
import org.apache.log4j.Logger;
import org.wso2.carbon.messaging.BinaryCarbonMessage;
import org.wso2.carbon.messaging.exceptions.ClientConnectorException;
import org.wso2.transport.file.connector.sender.VFSClientConnector;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static io.siddhi.extension.io.file.util.Constants.WAIT_TILL_DONE;
import static io.siddhi.query.api.definition.Attribute.Type.BOOL;
import static io.siddhi.query.api.definition.Attribute.Type.STRING;

/**
 * This extension can be used to copy files from a particular source to a destination.
 */
@Extension(
        name = "copy",
        namespace = "file",
        description = "This function performs copying file from a particular source to a destination.\n",
        parameters = {
                @Parameter(
                        name = "file.path",
                        description = "The file path of the source file to be copied.",
                        type = DataType.STRING
                ),
                @Parameter(
                        name = "destination.path",
                        description = "The file path of the destination folder of the file to be copied.",
                        type = DataType.STRING
                )
        },
        returnAttributes = {
                @ReturnAttribute(
                        description = "The success of the file copy.",
                        type = DataType.BOOL
                )
        },
        examples = {
                @Example(
                        syntax = "from CopyFileStream\n" +
                                "select file:copy('/User/wso2/source/test.txt', 'User/wso2/destination/') as copied\n" +
                                "insert into  ResultStream;",
                        description = "This query copies a particular file to a given path. " +
                                "The successfulness of the process will be returned as an boolean to the" +
                                "stream named 'RecordStream'."
                )
        }
)
public class FileCopyExtension extends FunctionExecutor {
    private SiddhiQueryContext siddhiQueryContext;
    private static final Logger log = Logger.getLogger(FileCopyExtension.class);
    Attribute.Type returnType = BOOL;
    @Override
    protected StateFactory init(ExpressionExecutor[] attributeExpressionExecutors, ConfigReader configReader,
                                SiddhiQueryContext siddhiQueryContext) {
        this.siddhiQueryContext = siddhiQueryContext;
        int executorsCount = attributeExpressionExecutors.length;
        if (executorsCount != 2) {
            throw new SiddhiAppValidationException("Invalid no of arguments passed to file:copy() function, "
                    + "required 2, but found " + executorsCount);
        }
        ExpressionExecutor executor1 = attributeExpressionExecutors[0];
        ExpressionExecutor executor2 = attributeExpressionExecutors[1];
        if (executor1.getReturnType() != STRING) {
            throw new SiddhiAppValidationException("Invalid parameter type found for the fileSource " +
                    "(first argument) of file:copy() function, required " + STRING.toString() + ", but found "
                    + executor1.getReturnType().toString());
        }
        if (executor2.getReturnType() != STRING) {
            throw new SiddhiAppValidationException("Invalid parameter type found for the fileDestination " +
                    "(second argument) of file:copy() function, required " + STRING.toString() + ", but found "
                    + executor1.getReturnType().toString());
        }
        return null;
    }

    @Override
    protected Object execute(Object[] data, State state) {
        VFSClientConnector vfsClientConnector = new VFSClientConnector();
        VFSClientConnectorCallback vfsClientConnectorCallback = new VFSClientConnectorCallback();
        String sourceFileUri = (String) data[0];
        String destinationDirUri = (String) data[1];
        BinaryCarbonMessage carbonMessage = new BinaryCarbonMessage(ByteBuffer.wrap(
                sourceFileUri.getBytes(StandardCharsets.UTF_8)), true);
        Map<String, String> properties = new HashMap<>();
        properties.put(Constants.ACTION, Constants.COPY);
        properties.put(Constants.URI, sourceFileUri);
        String destination = constructPath(destinationDirUri,
                getFileName(sourceFileUri, validateURLAndGetProtocol(sourceFileUri)));
        properties.put(Constants.DESTINATION, destination);
        try {
            vfsClientConnector.send(carbonMessage, vfsClientConnectorCallback, properties);
            vfsClientConnectorCallback.waitTillDone(WAIT_TILL_DONE, sourceFileUri);
        } catch (ClientConnectorException e) {
            throw new SiddhiAppRuntimeException("Failure occurred in vfs-client while moving the file " +
                    sourceFileUri, e);
        } catch (InterruptedException e) {
            throw new SiddhiAppRuntimeException("Failed to get callback from vfs-client for file " + sourceFileUri, e);
        }
        return true;
    }

    @Override
    protected Object execute(Object data, State state) {
        return null;
    }

    @Override
    public Attribute.Type getReturnType() {
        return returnType;
    }

    private String validateURLAndGetProtocol(String uri) {
        try {
            new URL(uri);
            String splitRegex = File.separatorChar == '\\' ? "\\\\" : File.separator;
            return uri.split(splitRegex)[0];
        } catch (MalformedURLException e) {
            throw new SiddhiAppRuntimeException(String.format("In 'file' source of siddhi app '" +
                            siddhiQueryContext.getSiddhiAppContext().getName() +
                    "', provided uri for destination '%s' is invalid.", uri), e);
        }
    }

    private String getFileName(String uri, String protocol) {
        try {
            URL url = new URL(String.format("%s%s%s", protocol, File.separator, uri));
            return FilenameUtils.getName(url.getPath());
        } catch (MalformedURLException e) {
            log.error(String.format("Failed to extract file name from the uri '%s'.", uri), e);
            return null;
        }
    }

    private String constructPath(String baseUri, String fileName) {
        if (baseUri != null && fileName != null) {
            if (baseUri.endsWith(File.separator)) {
                return String.format("%s%s", baseUri, fileName);
            } else {
                return String.format("%s%s%s", baseUri, File.separator, fileName);
            }
        } else {
            return null;
        }
    }
}