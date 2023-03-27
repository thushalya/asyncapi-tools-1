/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */


package io.ballerina.asyncapi.core.generators.asyncspec.service;

import io.apicurio.datamodels.models.asyncapi.v25.AsyncApi25ChannelsImpl;
import io.apicurio.datamodels.models.asyncapi.v25.AsyncApi25ComponentsImpl;
import io.apicurio.datamodels.models.asyncapi.v25.AsyncApi25DocumentImpl;
import io.ballerina.asyncapi.core.generators.asyncspec.diagnostic.AsyncAPIConverterDiagnostic;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.syntax.tree.*;


import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static io.ballerina.asyncapi.core.generators.asyncspec.Constants.*;

/**
 * AsyncAPIServiceMapper provides functionality for reading and writing AsyncApi, either to and from ballerina service, or
 * to, as well as related functionality for performing conversions between asyncapi and ballerina.
 *
 * @since 2.0.0
 */
public class AsyncAPIServiceMapper {
    private final SemanticModel semanticModel;
    private final List<AsyncAPIConverterDiagnostic> errors = new ArrayList<>();

    public List<AsyncAPIConverterDiagnostic> getErrors() {
        return errors;
    }

    /**
     * Initializes a service parser for AsyncApi.
     */
    public AsyncAPIServiceMapper(SemanticModel semanticModel) {
        // Default object mapper is JSON mapper available in asyncApi utils.
        this.semanticModel = semanticModel;
    }

    /**
     * This method will convert ballerina @Service to asyncApi @AsyncApi object.
     *
     * @param service   - Ballerina @Service object to be map to asyncApi definition
     * @param asyncApi   - AsyncApi model to populate
     * @return AsyncApi object which represent current service.
     */
    public AsyncApi25DocumentImpl convertServiceToAsyncAPI(ServiceDeclarationNode service, List<ClassDefinitionNode>classDefinitionNodes, AsyncApi25DocumentImpl asyncApi) {
        NodeList<Node> functions = service.members() ; //Take all resource functions

        String dispatcherValue= extractDispatcherValue(service); //Take dispatcherValue from @websocket:ServiceConfig annotation
        for(Node function: functions) {
            SyntaxKind kind = function.kind();
            if (kind.equals(SyntaxKind.RESOURCE_ACCESSOR_DEFINITION)) {
                AsyncAPIRemoteMapper resourceMapper = new AsyncAPIRemoteMapper(this.semanticModel);
                AsyncApi25ChannelsImpl generatedChannels = resourceMapper.getChannels((FunctionDefinitionNode) function, classDefinitionNodes, dispatcherValue);
                if (!generatedChannels.getItems().isEmpty()) {
                    asyncApi.setChannels(generatedChannels);
                }
                AsyncApi25ComponentsImpl generatedComponents = resourceMapper.getComponents();
                if (generatedComponents.getSchemas() != null || generatedComponents.getMessages() != null) {
                    asyncApi.setComponents(generatedComponents);
                }
                errors.addAll(resourceMapper.getErrors());
            }
        }
        return asyncApi;
    }

    private static String extractDispatcherValue(ServiceDeclarationNode service){
        String dispatcherValue=null;
        String typeName=null;
        if(service.metadata().isPresent()) {
            MetadataNode serviceMetadataNode = service.metadata().get();
            NodeList<AnnotationNode> annotationNodes = serviceMetadataNode.annotations();
            AnnotationNode annotationNode=annotationNodes.get(0);
            Node node = annotationNode.annotReference();
            if (node instanceof QualifiedNameReferenceNode) {
                QualifiedNameReferenceNode qNode = (QualifiedNameReferenceNode) node;
                if (qNode.modulePrefix().text().equals(WEBSOCKET)) {
                    typeName = qNode.modulePrefix().text() + ":" + qNode.identifier().text();
                    if (typeName.equals(WEBSOCKET + ":" + SERVICE_CONFIG)) {
                        SeparatedNodeList<MappingFieldNode> fields = annotationNode.annotValue().get().fields();
                        for (MappingFieldNode field : fields) {
                            if (field instanceof SpecificFieldNode) {
                                SpecificFieldNode specificFieldNode = (SpecificFieldNode) field;
                                String fieldName = specificFieldNode.fieldName().toString();
                                if (fieldName.equals(DISPATCHER_KEY)) {
                                    dispatcherValue = specificFieldNode.valueExpr().get().toString();
                                    if (dispatcherValue != null) {
                                        dispatcherValue = dispatcherValue.replaceAll("\"", "");
                                        if (dispatcherValue.equals("")) {
                                            //TODO : Give a proper name for Exception
                                            throw new NoSuchElementException(DISPATCHER_KEY_VALUE_CANNOT_BE_EMPTY);
                                        }
                                        return dispatcherValue;
                                    }
                                }
                            }
                        }
                        if (dispatcherValue == null) {
                            throw new NoSuchElementException(NO_DISPATCHER_KEY);
                        }
                    }else{
                        throw new NoSuchElementException(NO_WEBSOCKET_SERVICE_CONFIG_ANNOTATION);
                    }
                }
            }
        }else{
            throw new NoSuchElementException(NO_ANNOTATION_PRESENT);
        }
        return null;
    }
}
