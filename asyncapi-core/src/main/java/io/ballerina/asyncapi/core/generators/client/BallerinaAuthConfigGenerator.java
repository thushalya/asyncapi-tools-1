/*
 * Copyright (c) 2022, WSO2 LLC. (http://www.wso2.com). All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.asyncapi.core.generators.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.apicurio.datamodels.models.Schema;
import io.apicurio.datamodels.models.SecurityScheme;
import io.apicurio.datamodels.models.asyncapi.AsyncApiChannelItem;
import io.apicurio.datamodels.models.asyncapi.AsyncApiParameter;
import io.apicurio.datamodels.models.asyncapi.AsyncApiParameters;
import io.apicurio.datamodels.models.asyncapi.v25.*;
import io.ballerina.asyncapi.core.GeneratorConstants;
import io.ballerina.asyncapi.core.exception.BallerinaAsyncApiException;
import io.ballerina.compiler.syntax.tree.*;
import io.ballerina.asyncapi.core.generators.document.DocCommentsGenerator;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.ballerina.asyncapi.core.ErrorMessages.invalidPathParamType;
import static io.ballerina.asyncapi.core.GeneratorConstants.*;

import static io.ballerina.asyncapi.core.GeneratorUtils.*;
import static io.ballerina.asyncapi.core.generators.asyncspec.Constants.STRING;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createEmptyMinutiaeList;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createEmptyNodeList;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createIdentifierToken;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createLiteralValueToken;
import static io.ballerina.compiler.syntax.tree.NodeFactory.*;
import static io.ballerina.compiler.syntax.tree.SyntaxKind.*;

/**
 * This class is used to generate authentication related nodes of the ballerina connector client syntax tree.
 *
 * @since 1.3.0
 */
public class BallerinaAuthConfigGenerator {

    private final Map<String, String> headerApiKeyNameList = new HashMap<>();
    private final Map<String, String> queryApiKeyNameList = new HashMap<>();
    private final List<Node> apiKeysConfigRecordFields = new ArrayList<>();
    private final Set<String> authTypes = new LinkedHashSet<>();
    private boolean httpApiKey;
    private boolean httpOROAuth;
    private String clientCredGrantTokenUrl;
    private String passwordGrantTokenUrl;
    private String refreshTokenUrl;

    private AsyncApi25DocumentImpl asyncAPI;

    private BallerinaUtilGenerator ballerinaUtilGenerator;
    private List<TypeDefinitionNode> authRelatedTypeDefinitionNodes = new ArrayList<>();

    public BallerinaAuthConfigGenerator(boolean isAPIKey, boolean isHttpOROAuth,AsyncApi25DocumentImpl asyncAPI,
                                        BallerinaUtilGenerator ballerinaUtilGenerator) {
        this.httpApiKey = isAPIKey;
        this.httpOROAuth = isHttpOROAuth;
        this.asyncAPI = asyncAPI;
        this.ballerinaUtilGenerator=ballerinaUtilGenerator;
    }

    /**
     * Returns `true` if authentication mechanism is API key.
     *
     * @return {@link boolean}    values of the flag isAPIKey
     */
    public boolean isHttpApiKey() {
        return httpApiKey;
    }

    /**
     * Returns `true` if HTTP or OAuth authentication is supported.
     *
     * @return {@link boolean}
     */
    public boolean isHttpOROAuth() {

        return httpOROAuth;
    }

    /**
     * Returns API key names which need to send in the query string.
     *
     * @return {@link List<String>}    API key name list
     */
    public Map<String, String> getQueryApiKeyNameList() {
        return queryApiKeyNameList;
    }

    /**
     * Returns API key names which need to send as request headers.
     *
     * @return {@link List<String>}    API key name list
     */
    public Map<String, String> getHeaderApiKeyNameList() {
        return headerApiKeyNameList;
    }

    /**
     * Returns auth type to generate test file.
     *
     * @return {@link Set<String>}
     */
    public Set<String> getAuthType() {

        return authTypes;
    }

    public List<TypeDefinitionNode> getAuthRelatedTypeDefinitionNodes() {
        return authRelatedTypeDefinitionNodes;
    }

    /**
     * Add authentication related records.
     *
     * @param asyncAPI AsyncAPI object received from apicurio parser
     * @throws BallerinaAsyncApiException When function fails
     */
    public void addAuthRelatedRecords(AsyncApi25DocumentImpl asyncAPI) throws
            BallerinaAsyncApiException {
        List<TypeDefinitionNode> nodes = new ArrayList<>();
        if (asyncAPI.getComponents() != null) {
            // set auth types
            if (asyncAPI.getComponents().getSecuritySchemes() != null) {
                Map<String, SecurityScheme> securitySchemeMap = asyncAPI.getComponents().getSecuritySchemes();
                setAuthTypes(securitySchemeMap);
            }

            // generate related records
//            TypeDefinitionNode connectionConfigRecord = generateConnectionConfigRecord();
//            nodes.add(connectionConfigRecord);

//            TypeDefinitionNode clientHttp1SettingsRecord = getClientHttp1SettingsRecord();
//            TypeDefinitionNode customProxyConfigRecord = getCustomProxyRecord();
//            nodes.addAll(Arrays.asList(connectionConfigRecord, clientHttp1SettingsRecord, customProxyConfigRecord));


            if (isHttpApiKey()) {
                nodes.add(generateApiKeysConfig());
            }

            // Add custom `OAuth2ClientCredentialsGrantConfig` record with default tokenUrl if `tokenUrl` is available
            if (clientCredGrantTokenUrl != null) {
                nodes.add(getOAuth2ClientCredsGrantConfigRecord());
            }

            // Add custom `OAuth2PasswordGrantConfig` record with default tokenUrl if `tokenUrl` is available
            if (passwordGrantTokenUrl != null) {
                nodes.add(getOAuth2PasswordGrantConfigRecord());
            }

            // Add custom `OAuth2RefreshTokenGrantConfig` record with default refreshUrl if `refreshUrl` is available
            if (refreshTokenUrl != null) {
                nodes.add(getOAuth2RefreshTokenGrantConfigRecord());
            }
        }
        this.authRelatedTypeDefinitionNodes = nodes;
    }

    /**
     * Generate the Config record for the relevant authentication type.
     * -- ex: Config record for Http and OAuth 2.0 Authentication mechanisms.
     * <pre>
     * # Provides a set of configurations for controlling the behaviours when communicating with a remote WEBSOCKET
     * service endpoint.
     * public type ConnectionConfig record {|
     *          # Configurations related to client authentication
     *          websocket:BearerTokenConfig|websocket:OAuth2RefreshTokenGrantConfig auth;
     *          # Negotiable sub protocols of the client
     *          string[] subProtocols = [];
     *          # Custom headers, which should be sent to the server
     *          map<string> customHeaders = {};
     *          # Read timeout (in seconds) of the client
     *          decimal readTimeout = -1;
     *          # Write timeout (in seconds) of the client
     *          decimal writeTimeout = -1;
     *          # SSL/TLS-related options
     *          websocket:ClientSecureSocket? secureSocket = ();
     *          # The maximum payload size of a WebSocket frame in bytes
     *          int maxFrameSize = 65536;
     *          # Enable support for compression in the WebSocket
     *          boolean webSocketCompressionEnabled = true;
     *          # Time (in seconds) that a connection waits to get the response of the WebSocket handshake.
     *          decimal handShakeTimeout = 300;
     *          # An Array of http:Cookie
     *          http:Cookie[] cookies?;
     *          # A service to handle the ping/pong frames.
     *          PingPongService pingPongHandler?;
     *          # Configurations associated with retrying
     *          websocket:WebSocketRetryConfig retryConfig? = ();
     *          Enable/disable constraint validation
     *          boolean validation = true;Z
     * |};
     * </pre>
     * Scenario 1 : For asyncapi contracts with no authentication mechanism given, auth field will not be generated
     * Scenario 2 : For asyncapi contracts with authentication mechanism, auth field in relevant types will be generated
     * Scenario 3 : For asyncapi contracts with only apikey authentication mechanism, auth field will not be generated
     * Scenario 4 : For asyncapi contracts with both http and apikey authentication mechanisms given,
     * auth field in relevant types will be generated
     *
     * @return {@link TypeDefinitionNode}   Syntax tree node of config record
     */
    public TypeDefinitionNode generateConnectionConfigRecord() {

//        AnnotationNode annotationNode = getDisplayAnnotationForRecord("Connection Config");
//        MetadataNode configRecordMetadataNode = getMetadataNode(
//                "Provides a set of configurations for controlling the behaviours when communicating " +
//                        "with a remote HTTP endpoint.", Collections.singletonList(annotationNode));
        Token typeName = AbstractNodeFactory.createIdentifierToken(CONNECTION_CONFIG);
        NodeList<Node> recordFieldList = createNodeList(getClientConfigRecordFields());

        RecordTypeDescriptorNode recordTypeDescriptorNode =
                NodeFactory.createRecordTypeDescriptorNode(createToken(RECORD_KEYWORD),
                        createToken(OPEN_BRACE_PIPE_TOKEN), recordFieldList, null,
                        createToken(CLOSE_BRACE_PIPE_TOKEN));
//        TypeDefinitionNode node = NodeFactory.createTypeDefinitionNode(configRecordMetadataNode,
//                createToken(PUBLIC_KEYWORD), createToken(TYPE_KEYWORD), typeName,
//                recordTypeDescriptorNode, createToken(SEMICOLON_TOKEN));
        TypeDefinitionNode node = NodeFactory.createTypeDefinitionNode(null,
                createToken(PUBLIC_KEYWORD), createToken(TYPE_KEYWORD), typeName,
                recordTypeDescriptorNode, createToken(SEMICOLON_TOKEN));
        return node;

    }

    /**
     * Generate the ApiKeysConfig record when the http-api-key auth type is given.
     * <pre>
     *  # Provides API key configurations needed when communicating with a remote WEBSOCKET service endpoint.
     *  public type ApiKeysConfig record {|
     *     # Represents API Key `Authorization`
     *     string authorization;
     *     # Represents API Key `apikey`
     *     string apikey;
     *  |};
     * </pre>
     *
     * @return {@link TypeDefinitionNode}   Syntax tree node of config record
     */
    public TypeDefinitionNode generateApiKeysConfig() {
        MetadataNode configRecordMetadataNode = getMetadataNode(
                "Provides API key configurations needed when communicating " +
                        "with a remote WEBSOCKET service endpoint.");
        Token typeName = AbstractNodeFactory.createIdentifierToken(API_KEYS_CONFIG);
        NodeList<Node> recordFieldList = createNodeList(apiKeysConfigRecordFields);
        RecordTypeDescriptorNode recordTypeDescriptorNode =
                NodeFactory.createRecordTypeDescriptorNode(createToken(RECORD_KEYWORD),
                        createToken(OPEN_BRACE_PIPE_TOKEN), recordFieldList, null,
                        createToken(CLOSE_BRACE_PIPE_TOKEN));
        return NodeFactory.createTypeDefinitionNode(configRecordMetadataNode,
                createToken(PUBLIC_KEYWORD), createToken(TYPE_KEYWORD), typeName,
                recordTypeDescriptorNode, createToken(SEMICOLON_TOKEN));
    }

    /**
     * Create `OAuth2ClientCredentialsGrantConfig` record with default tokenUrl.
     *
     * <pre>
     *      # OAuth2 Client Credentials Grant Configs
     *      public type OAuth2ClientCredentialsGrantConfig record {|
     *          *websocket:OAuth2ClientCredentialsGrantConfig;
     *          # Token URL
     *          string tokenUrl = "https://zoom.us/oauth/token";
     *      |};
     * </pre>
     *
     * @return {@link TypeDefinitionNode}   Custom `OAuth2ClientCredentialsGrantConfig` record with default tokenUrl
     */
    private TypeDefinitionNode getOAuth2ClientCredsGrantConfigRecord() {
        Token typeName = AbstractNodeFactory.createIdentifierToken(AuthConfigTypes.CUSTOM_CLIENT_CREDENTIAL.getValue());
        NodeList<Node> recordFieldList = createNodeList(getClientCredsGrantConfigFields());
        MetadataNode configRecordMetadataNode = getMetadataNode("OAuth2 Client Credentials Grant Configs");
        RecordTypeDescriptorNode recordTypeDescriptorNode =
                NodeFactory.createRecordTypeDescriptorNode(createToken(RECORD_KEYWORD),
                        createToken(OPEN_BRACE_PIPE_TOKEN), recordFieldList, null,
                        createToken(CLOSE_BRACE_PIPE_TOKEN));
        return NodeFactory.createTypeDefinitionNode(configRecordMetadataNode,
                createToken(PUBLIC_KEYWORD), createToken(TYPE_KEYWORD), typeName,
                recordTypeDescriptorNode, createToken(SEMICOLON_TOKEN));
    }

    /**
     * Generates fields of `OAuth2ClientCredentialsGrantConfig` record with default tokenUrl.
     *
     * <pre>
     *      *websocket:OAuth2ClientCredentialsGrantConfig;
     *      # Token URL
     *      string tokenUrl = "https://zoom.us/oauth/token";
     * </pre>
     *
     * @return {@link List<Node>}
     */
    private List<Node> getClientCredsGrantConfigFields() {
        List<Node> recordFieldNodes = new ArrayList<>();
        Token semicolonToken = createToken(SEMICOLON_TOKEN);
        Token equalToken = createToken(EQUAL_TOKEN);

        recordFieldNodes.add(createIncludedRecordParameterNode(createEmptyNodeList(),
                createToken(ASTERISK_TOKEN),
                createIdentifierToken("websocket:OAuth2ClientCredentialsGrantConfig;"), null));

        MetadataNode metadataNode = getMetadataNode("Token URL");
        TypeDescriptorNode stringType = createSimpleNameReferenceNode(createToken(STRING_KEYWORD));
        IdentifierToken fieldNameTokenUrl = createIdentifierToken("tokenUrl");
        ExpressionNode defaultValue = createRequiredExpressionNode(createIdentifierToken("\"" +
                clientCredGrantTokenUrl + "\""));
        RecordFieldWithDefaultValueNode fieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                metadataNode, null, stringType, fieldNameTokenUrl, equalToken, defaultValue,
                semicolonToken);
        recordFieldNodes.add(fieldNode);
        return recordFieldNodes;
    }

    /**
     * Create `OAuth2PasswordGrantConfig` record with default tokenUrl.
     *
     * <pre>
     *      # OAuth2 Password Grant Configs
     *      public type OAuth2PasswordGrantConfig record {|
     *          *websocket:OAuth2PasswordGrantConfig;
     *          # Token URL
     *          string tokenUrl = "https://zoom.us/oauth/token";
     *      |};
     * </pre>
     *
     * @return {@link TypeDefinitionNode}   Custom `OAuth2PasswordGrantConfig` record with default tokenUrl
     */
    private TypeDefinitionNode getOAuth2PasswordGrantConfigRecord() {
        Token typeName = AbstractNodeFactory.createIdentifierToken(AuthConfigTypes.CUSTOM_PASSWORD.getValue());
        NodeList<Node> recordFieldList = createNodeList(getPasswordGrantConfigFields());
        MetadataNode configRecordMetadataNode = getMetadataNode("OAuth2 Password Grant Configs");
        RecordTypeDescriptorNode recordTypeDescriptorNode =
                NodeFactory.createRecordTypeDescriptorNode(createToken(RECORD_KEYWORD),
                        createToken(OPEN_BRACE_PIPE_TOKEN), recordFieldList, null,
                        createToken(CLOSE_BRACE_PIPE_TOKEN));
        return NodeFactory.createTypeDefinitionNode(configRecordMetadataNode,
                createToken(PUBLIC_KEYWORD), createToken(TYPE_KEYWORD), typeName,
                recordTypeDescriptorNode, createToken(SEMICOLON_TOKEN));
    }

    /**
     * Generates fields of `OAuth2PasswordGrantConfig` record with default tokenUrl.
     *
     * <pre>
     *      *websocket:OAuth2PasswordGrantConfig;
     *      # Token URL
     *      string tokenUrl = "https://zoom.us/oauth/token";
     * </pre>
     *
     * @return {@link List<Node>}
     */
    private List<Node> getPasswordGrantConfigFields() {

        List<Node> recordFieldNodes = new ArrayList<>();
        Token semicolonToken = createToken(SEMICOLON_TOKEN);
        Token equalToken = createToken(EQUAL_TOKEN);

        recordFieldNodes.add(createIncludedRecordParameterNode(createEmptyNodeList(),
                createToken(ASTERISK_TOKEN),
                createIdentifierToken("websocket:OAuth2PasswordGrantConfig;"), null));

        MetadataNode metadataNode = getMetadataNode("Token URL");
        TypeDescriptorNode stringType = createSimpleNameReferenceNode(createToken(STRING_KEYWORD));
        IdentifierToken fieldNameTokenUrl = createIdentifierToken("tokenUrl");
        ExpressionNode defaultValue = createRequiredExpressionNode(createIdentifierToken("\"" +
                passwordGrantTokenUrl + "\""));
        RecordFieldWithDefaultValueNode fieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                metadataNode, null, stringType, fieldNameTokenUrl, equalToken, defaultValue,
                semicolonToken);
        recordFieldNodes.add(fieldNode);
        return recordFieldNodes;
    }

    /**
     * Create `OAuth2RefreshTokenGrantConfig` record with default refreshUrl.
     *
     * <pre>
     *      # OAuth2 Refresh Token Grant Configs
     *      public type OAuth2RefreshTokenGrantConfig record {|
     *          *websocket:OAuth2RefreshTokenGrantConfig;
     *          # Refresh URL
     *          string refreshUrl = "https://zoom.us/oauth/token";
     *      |};
     * </pre>
     *
     * @return {@link TypeDefinitionNode}   Custom `OAuth2RefreshTokenGrantConfig` record with default refreshUrl
     */
    private TypeDefinitionNode getOAuth2RefreshTokenGrantConfigRecord() {
        Token typeName = AbstractNodeFactory.createIdentifierToken(AuthConfigTypes.CUSTOM_REFRESH_TOKEN.getValue());
        NodeList<Node> recordFieldList = createNodeList(getRefreshTokenGrantConfigFields());
        MetadataNode configRecordMetadataNode = getMetadataNode("OAuth2 Refresh Token Grant Configs");
        RecordTypeDescriptorNode recordTypeDescriptorNode =
                NodeFactory.createRecordTypeDescriptorNode(createToken(RECORD_KEYWORD),
                        createToken(OPEN_BRACE_PIPE_TOKEN), recordFieldList, null,
                        createToken(CLOSE_BRACE_PIPE_TOKEN));
        return NodeFactory.createTypeDefinitionNode(configRecordMetadataNode,
                createToken(PUBLIC_KEYWORD), createToken(TYPE_KEYWORD), typeName,
                recordTypeDescriptorNode, createToken(SEMICOLON_TOKEN));
    }

    /**
     * Generates fields of `OAuth2RefreshTokenGrantConfig` record with default refreshUrl.
     *
     * <pre>
     *      *websocket:OAuth2RefreshTokenGrantConfig;
     *      # Refresh URL
     *      string refreshUrl = "https://zoom.us/oauth/token";
     * </pre>
     *
     * @return {@link List<Node>}
     */
    private List<Node> getRefreshTokenGrantConfigFields() {

        List<Node> recordFieldNodes = new ArrayList<>();
        Token semicolonToken = createToken(SEMICOLON_TOKEN);
        Token equalToken = createToken(EQUAL_TOKEN);

        recordFieldNodes.add(createIncludedRecordParameterNode(createEmptyNodeList(),
                createToken(ASTERISK_TOKEN),
                createIdentifierToken("websocket:OAuth2RefreshTokenGrantConfig;"), null));

        MetadataNode metadataNode = getMetadataNode("Refresh URL");
        TypeDescriptorNode stringType = createSimpleNameReferenceNode(createToken(STRING_KEYWORD));
        IdentifierToken fieldNameTokenUrl = createIdentifierToken("refreshUrl");
        ExpressionNode defaultValue = createRequiredExpressionNode(createIdentifierToken("\"" +
                refreshTokenUrl + "\""));
        RecordFieldWithDefaultValueNode fieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                metadataNode, null, stringType, fieldNameTokenUrl, equalToken, defaultValue,
                semicolonToken);
        recordFieldNodes.add(fieldNode);
        return recordFieldNodes;
    }


    /**
     * Generate Class variable for api key map {@code final readonly & ApiKeysConfig apiKeyConfig;}.
     *
     * @return {@link List<ObjectFieldNode>}    syntax tree object field node list
     */
    public ObjectFieldNode getApiKeyMapClassVariable() { // return ObjectFieldNode
        if (httpApiKey) {
            NodeList<Token> qualifierList = createNodeList(createToken(FINAL_KEYWORD));
            TypeDescriptorNode readOnlyNode = createTypeReferenceTypeDescNode(createSimpleNameReferenceNode
                    (createToken(READONLY_KEYWORD)));
            TypeDescriptorNode apiKeyMapNode = createSimpleNameReferenceNode(createIdentifierToken(API_KEYS_CONFIG));
            if (httpOROAuth) {
                apiKeyMapNode = createOptionalTypeDescriptorNode(apiKeyMapNode, createToken(QUESTION_MARK_TOKEN));
            }
            TypeDescriptorNode intersectionTypeDescriptorNode = createIntersectionTypeDescriptorNode(readOnlyNode,
                    createToken(BITWISE_AND_TOKEN), apiKeyMapNode);
            IdentifierToken fieldName = createIdentifierToken(API_KEY_CONFIG_PARAM);
            MetadataNode metadataNode = createMetadataNode(null, createEmptyNodeList());
            return createObjectFieldNode(metadataNode, null,
                    qualifierList, intersectionTypeDescriptorNode, fieldName, null, null,
                    createToken(SEMICOLON_TOKEN));
        }
        return null;
    }

    /**
     * Generate the config parameters of the client class init method.
     * -- ex: Config param for Http and OAuth 2.0 Authentication mechanisms.
     * {@code ClientConfig clientConfig, string serviceUrl = "https://asyncapi.com:443/v2" }
     * -- ex: Config param for API Key Authentication mechanism.
     * {@code ApiKeysConfig apiKeyConfig, http:ClientConfiguration clientConfig = {},
     * string serviceUrl = "https://asyncapi.com:443/v2" }
     * Config param for API Key Authentication mechanism with no server URL given
     * {@code ApiKeysConfig apiKeyConfig, string serviceUrl; http:ClientConfiguration clientConfig = {}}
     * -- ex: Config param when no authentication mechanism given.
     * {@code http:ClientConfiguration clientConfig = {},
     * string serviceUrl = "https://asyncapi.com:443/v2" }
     * Config param when no authentication mechanism given with no server URL
     * {@code string serviceUrl, http:ClientConfiguration clientConfig = {}}
     * -- ex: Config param for combination of API Key and Http or OAuth 2.0 Authentication mechanisms.
     * {@code AuthConfig authConfig,ConnectionConfig config =  {},
     * string serviceUrl = "https://asyncapi.com:443/v2" }
     *
     * @return {@link List<Node>}  syntax tree node list of config parameters
     */
    public List<Node> getConfigParamForClassInit(String serviceUrl,ArrayList initMetaDoc) throws BallerinaAsyncApiException, JsonProcessingException {

        NodeList<AnnotationNode> annotationNodes = createEmptyNodeList();
        Node serviceURLNode = getServiceURLNode(serviceUrl);
        List<Node> parameters = new ArrayList<>();
        IdentifierToken equalToken = createIdentifierToken(GeneratorConstants.EQUAL);
        if (httpOROAuth) {
            BuiltinSimpleNameReferenceNode typeName = createBuiltinSimpleNameReferenceNode(null,
                    createIdentifierToken(CONNECTION_CONFIG));
            IdentifierToken paramName = createIdentifierToken(CONFIG);
            RequiredParameterNode authConfig = createRequiredParameterNode(annotationNodes, typeName, paramName);
            parameters.add(authConfig);
            parameters.add(createToken(COMMA_TOKEN));
            parameters.add(serviceURLNode);
        } else {
            if (httpApiKey) {
                BuiltinSimpleNameReferenceNode apiKeyConfigTypeName = createBuiltinSimpleNameReferenceNode(null,
                        createIdentifierToken(API_KEYS_CONFIG));
                IdentifierToken apiKeyConfigParamName = createIdentifierToken(API_KEY_CONFIG_PARAM);
                RequiredParameterNode apiKeyConfigParamNode = createRequiredParameterNode(annotationNodes,
                        apiKeyConfigTypeName, apiKeyConfigParamName);
                parameters.add(apiKeyConfigParamNode);
                parameters.add(createToken(COMMA_TOKEN));
            }

            BuiltinSimpleNameReferenceNode websocketClientConfigTypeName = createBuiltinSimpleNameReferenceNode(
                    null, createIdentifierToken(CONNECTION_CONFIG));
            IdentifierToken httpClientConfig = createIdentifierToken(CONFIG);
            BasicLiteralNode emptyExpression = createBasicLiteralNode(null, createIdentifierToken(" {}"));
            DefaultableParameterNode defaultConnectionConfig = createDefaultableParameterNode(annotationNodes,
                    websocketClientConfigTypeName,
                    httpClientConfig, equalToken, emptyExpression);
            if (serviceURLNode instanceof RequiredParameterNode) {
                parameters.add(serviceURLNode);
                parameters.add(createToken(COMMA_TOKEN));
                parameters.add(defaultConnectionConfig);
            } else {
                parameters.add(defaultConnectionConfig);
                parameters.add(createToken(COMMA_TOKEN));
                parameters.add(serviceURLNode);
            }
        }

//        string path - common for every remote functions
//        VariableDeclarationNode pathInt = getPathStatement(serviceUrl, annotationNodes);
//        statementsList.add(pathInt);
////
        //TODO: move this
        //Handle query parameter map
//        handleParameterSchemaInOperation(operation, statementsList);
        setFunctionParameters(asyncAPI.getChannels().getItems().get(0), parameters, createToken(COMMA_TOKEN),initMetaDoc);
        return parameters;
    }



    /**
     * Generate function parameters.
     */
    private void setFunctionParameters(AsyncApiChannelItem channelItem, List<Node> parameterList, Token comma,
                                       List<Node> remoteFunctionDoc) throws BallerinaAsyncApiException, JsonProcessingException {

        AsyncApi25ParametersImpl parameters = (AsyncApi25ParametersImpl) channelItem.getParameters();
        AsyncApi25ChannelBindingsImpl bindings=(AsyncApi25ChannelBindingsImpl) channelItem.getBindings();
        if(bindings!=null && bindings.getWs()==null){
            throw new BallerinaAsyncApiException("This tool support only for websocket protocol,use ws bindings");
        }
        AsyncApi25BindingImpl wsBindings=(AsyncApi25BindingImpl) bindings.getWs();
//            wsBindings.

        List<Node> defaultable = new ArrayList<>();
        List<Node> deprecatedParamDocComments = new ArrayList<>();

        if (parameters != null) {
            for (String parameterName : parameters.getItemNames()) {
                if (parameters.getItem(parameterName).getDescription() != null && !parameters.getItem(parameterName).
                        getDescription().isBlank()) {
                    MarkdownParameterDocumentationLineNode paramAPIDoc =
                            DocCommentsGenerator.createAPIParamDoc(getValidName(
                                    parameterName, false), parameters.getItem(parameterName).getDescription());
                    remoteFunctionDoc.add(paramAPIDoc);
                }
//                List<AnnotationNode> parameterAnnotationNodeList =
//                        getParameterAnnotationNodeList((AsyncApi25ParameterImpl) parameters.getItem(parameterName),
//                                deprecatedParamDocComments);

                Node param = getPathParameters(parameterName, (AsyncApi25ParameterImpl) parameters.getItem(parameterName),
                        createNodeList(new ArrayList<>()));
                parameterList.add(param);
            }
        }

        if(wsBindings.getItem("headers")!=null){
            JsonNode headers=wsBindings.getItem("headers");
            ObjectMapper objMapper=new ObjectMapper();

            AsyncApi25SchemaImpl headerSchema=objMapper.treeToValue(headers,AsyncApi25SchemaImpl.class);
            Map<String, Schema>  properties=headerSchema.getProperties();
//            properties


        }
        if (wsBindings.getItem("query")!=null){
            JsonNode query=wsBindings.getItem("query");
            ObjectMapper objMapper=new ObjectMapper();

            AsyncApi25SchemaImpl querySchema=objMapper.treeToValue(query,AsyncApi25SchemaImpl.class);
            Map<String, Schema>  properties=querySchema.getProperties();

            for (String queryName : properties.keySet()) {
                Node param= getQueryParameters((AsyncApi25SchemaImpl)properties.get(queryName),queryName,
                        createNodeList(new ArrayList<>()));
                parameterList.add(param);
            }

        }
//        if (((AsyncApi25BindingImpl)bindings)..)
    }


//                String in = parameter.getIn();
//                switch (in) {
//                    case "path":
//                        Node param = getPathParameters(paramete, createNodeList(parameterAnnotationNodeList));
//                        // Path parameters are always required.
//                        if (!isResource) {
//                            parameterList.add(param);
//                            parameterList.add(comma);
//                        }
//                        break;
//                    case "query":
//                        Node paramq = getQueryParameters(parameter, createNodeList(parameterAnnotationNodeList));
//                        if (paramq instanceof RequiredParameterNode) {
//                            parameterList.add(paramq);
//                            parameterList.add(comma);
//                        } else {
//                            defaultable.add(paramq);
//                            defaultable.add(comma);
//                        }
//                        break;
//                    case "header":
//                        Node paramh = getHeaderParameter(parameter, createNodeList(parameterAnnotationNodeList));
//                        if (paramh instanceof RequiredParameterNode) {
//                            parameterList.add(paramh);
//                            parameterList.add(comma);
//                        } else {
//                            defaultable.add(paramh);
//                            defaultable.add(comma);
//                        }
//                        break;
//                    default:
//                        break;
//                }




//
//        // Handle RequestBody
//        if (operation.getRequestBody() != null) {
//            setRequestBodyParameters(operation.getOperationId(), operation.getRequestBody(), remoteFunctionDoc,
//                    parameterList, defaultable);
//        }
//        remoteFunctionDoc.addAll(deprecatedParamDocComments);
//        //Filter defaultable parameters
//        if (!defaultable.isEmpty()) {
//            parameterList.addAll(defaultable);
//        }




    /**
     * Create path parameters.
     */
    public Node getPathParameters(String parameterName,AsyncApi25ParameterImpl parameter,
                                  NodeList<AnnotationNode> parameterAnnotationNodeList)
            throws BallerinaAsyncApiException {

        IdentifierToken paramName = createIdentifierToken(getValidName(parameterName, false));
        String type = "";
        AsyncApi25SchemaImpl parameterSchema =( AsyncApi25SchemaImpl)parameter.getSchema();
        if (parameterSchema.get$ref() != null) {
            type = getValidName(extractReferenceType(parameterSchema.get$ref()), true);
            AsyncApi25SchemaImpl schema = (AsyncApi25SchemaImpl) asyncAPI.getComponents().getSchemas().
                    get(type.trim());
            if (schema.getType().equals("object")) {
                throw new BallerinaAsyncApiException("Ballerina does not support object type path parameters.");
            }
        } else {
            type = convertAsyncAPITypeToBallerina(parameter.getSchema().getType().trim());
            if (type.equals("anydata") || type.equals(SQUARE_BRACKETS) || type.equals("record {}")) {
                throw new BallerinaAsyncApiException(invalidPathParamType(parameterName.trim()));
            }
        }
        BuiltinSimpleNameReferenceNode typeName = createBuiltinSimpleNameReferenceNode(null,
                createIdentifierToken(type));
        return createRequiredParameterNode(parameterAnnotationNodeList, typeName, paramName);
    }

    /**
     * Create query parameters.
     * <p>
     * Note: currently ballerina supports for this data type.
     * type BasicType boolean|int|float|decimal|string;
     * public type QueryParamType ()|BasicType|BasicType[];
     */
    public Node getQueryParameters(AsyncApi25SchemaImpl schema, String queryParamName, NodeList<AnnotationNode> parameterAnnotationNodeList)
            throws BallerinaAsyncApiException, JsonProcessingException {

        TypeDescriptorNode typeName;

//        Schema parameterSchema = parameter.getSchema();
        String paramType = "";
//        if (parameterSchema.get$ref() != null) {
//            paramType = getValidName(extractReferenceType(parameterSchema.get$ref()), true);
//        } else {
            paramType = convertAsyncAPITypeToBallerina(schema.getType().trim());
            if (schema.getType().equals(NUMBER)) {
                if (schema.getFormat() != null) {
                    paramType = convertAsyncAPITypeToBallerina(schema.getFormat().trim());
                }
            } else if (schema.getType().equals("array")) {
                ObjectMapper objMapper=new ObjectMapper();
               AsyncApi25SchemaImpl itemsSchema=objMapper.treeToValue(schema.getItems(),AsyncApi25SchemaImpl.class);
                if (itemsSchema.getType() != null) {
                    String itemType = itemsSchema.getType();
                    if (itemType.equals(STRING) || itemType.equals(INTEGER) || itemType.equals(BOOLEAN)) {
                        paramType = convertAsyncAPITypeToBallerina(itemType) + SQUARE_BRACKETS;
                    } else if (itemType.equals(NUMBER)) {
                        paramType = convertAsyncAPITypeToBallerina
                                (itemsSchema.getFormat().trim()) + SQUARE_BRACKETS;
                    } else {
                        throw new BallerinaAsyncApiException("Unsupported parameter type is found in the parameter : " +
                                queryParamName);
                    }
                } else if (itemsSchema.get$ref() != null) {
                    paramType = getValidName(extractReferenceType(
                            itemsSchema.get$ref().trim()), true) + SQUARE_BRACKETS;
                } else {
                    throw new BallerinaAsyncApiException("Please define the array item type of the parameter : " +
                            queryParamName);
                }
            }



         if(schema.getDefault()!=null) {
            IdentifierToken paramName =
                    createIdentifierToken(getValidName(queryParamName.trim(), false));
            // Handle given default values in query parameter.
//            if (schema.getDefault() != null) {
                LiteralValueToken literalValueToken;
                if (schema.getType().equals(STRING)) {
                    literalValueToken = createLiteralValueToken(null,
                            '"' + schema.getDefault().toString() + '"', createEmptyMinutiaeList(),
                            createEmptyMinutiaeList());
                } else {
                    literalValueToken =
                            createLiteralValueToken(null,schema.getDefault().toString(),
                                    createEmptyMinutiaeList(),
                                    createEmptyMinutiaeList());

                }
                if(schema.getExtensions()!=null && schema.getExtensions().get("x-nullable")!=null &&
                        schema.getExtensions().get("x-nullable").equals(BooleanNode.TRUE)){
                    typeName = createOptionalTypeDescriptorNode(createBuiltinSimpleNameReferenceNode(null,
                            createIdentifierToken(paramType)), createToken(QUESTION_MARK_TOKEN));
                    return createDefaultableParameterNode(parameterAnnotationNodeList, typeName, paramName,
                            createToken(EQUAL_TOKEN), literalValueToken);

                }else{
                    typeName = createBuiltinSimpleNameReferenceNode(null, createIdentifierToken(paramType));
                    return createDefaultableParameterNode(parameterAnnotationNodeList, typeName, paramName,
                            createToken(EQUAL_TOKEN), literalValueToken);

                }

//            } else {
//                typeName = createOptionalTypeDescriptorNode(createBuiltinSimpleNameReferenceNode(null,
//                        createIdentifierToken(paramType)), createToken(QUESTION_MARK_TOKEN));
//                NilLiteralNode nilLiteralNode =
//                        createNilLiteralNode(createToken(OPEN_PAREN_TOKEN), createToken(CLOSE_PAREN_TOKEN));
//                return createDefaultableParameterNode(parameterAnnotationNodeList, typeName, paramName,
//                        createToken(EQUAL_TOKEN), nilLiteralNode);
//            }
        }else{
            typeName = createBuiltinSimpleNameReferenceNode(null, createIdentifierToken(paramType));
            IdentifierToken paramName =
                    createIdentifierToken(getValidName(queryParamName.trim(), false));
            return createRequiredParameterNode(parameterAnnotationNodeList, typeName, paramName);
        }
    }

//    private List<AnnotationNode> getParameterAnnotationNodeList(AsyncApi25ParameterImpl parameter,
//                                                                List<Node> deprecatedParamDocComments) {
//
//        List<AnnotationNode> parameterAnnotationNodeList = new ArrayList<>();
//        Map<String, JsonNode> extensions= parameter.getExtensions();
////        DocCommentsGenerator.extractDisplayAnnotation(parameter.getExtensions(), parameterAnnotationNodeList);
//
//        if (parameter.getDeprecated() != null && parameter.getDeprecated()) {
//            if (!this.deprecatedParamFound) {
//                deprecatedParamDocComments.addAll(DocCommentsGenerator.createAPIDescriptionDoc(
//                        "# Deprecated parameters", false));
//                this.deprecatedParamFound = true;
//            }
//            String deprecatedDescription = "";
//            if (parameter.getExtensions() != null) {
//                for (Map.Entry<String, Object> extension : parameter.getExtensions().entrySet()) {
//                    if (extension.getKey().trim().equals(X_BALLERINA_DEPRECATED_REASON)) {
//                        deprecatedDescription = extension.getValue().toString();
//                        break;
//                    }
//                }
//            }
//            MarkdownParameterDocumentationLineNode paramAPIDoc =
//                    DocCommentsGenerator.createAPIParamDoc(getValidName(
//                            parameter.getName(), false), deprecatedDescription);
//            deprecatedParamDocComments.add(paramAPIDoc);
//            parameterAnnotationNodeList.add(createAnnotationNode(createToken(AT_TOKEN),
//                    createSimpleNameReferenceNode(createIdentifierToken("deprecated")), null));
//        }
//        return parameterAnnotationNodeList;
//    }




    /**
     * This method use to generate Path statement inside the function body node.
     * <p>
     * ex:
     * <pre> string  path = string `/weather`; </pre>
     *
     * @param path            - Given path
     * @param annotationNodes - Node list for path implementation
     * @return - VariableDeclarationNode for path statement.
     */
    private VariableDeclarationNode getPathStatement(String path, NodeList<AnnotationNode> annotationNodes) {

        TypedBindingPatternNode typedBindingPatternNode = createTypedBindingPatternNode(createSimpleNameReferenceNode(
                createToken(STRING_KEYWORD)), createCaptureBindingPatternNode(
                createIdentifierToken(RESOURCE_PATH)));
        // Create initializer
        // Content  should decide with /pet and /pet/{pet}
        path = generatePathWithPathParameter(path);
        //String path generator
        NodeList<Node> content = createNodeList(createLiteralValueToken(null, path, createEmptyMinutiaeList(),
                createEmptyMinutiaeList()));
        TemplateExpressionNode initializer = createTemplateExpressionNode(null, createToken(STRING_KEYWORD),
                createToken(BACKTICK_TOKEN), content, createToken(BACKTICK_TOKEN));
        return createVariableDeclarationNode(annotationNodes, null,
                typedBindingPatternNode, createToken(EQUAL_TOKEN), initializer, createToken(SEMICOLON_TOKEN));
    }


        /**
     * This method is to used for generating path when it has path parameters.
     *
     * @param path - yaml contract path
     * @return string of path
     */
    public String generatePathWithPathParameter(String path) {

        if (path.contains("{")) {
            String refinedPath = path;
            Pattern p = Pattern.compile("\\{[^}]*}");
            Matcher m = p.matcher(path);
            while (m.find()) {
                String pathVariable = path.substring(m.start(), m.end());
                if (pathVariable.startsWith("{") && pathVariable.endsWith("}")) {
                    String d = pathVariable.replace("{", "").replace("}", "");
                    String replaceVariable = "{getEncodedUri(" + getValidName(d, false) + ")}";
                    refinedPath = refinedPath.replace(pathVariable, replaceVariable);
                }
            }
            path = refinedPath.replaceAll("[{]", "\\${");
        }
        ballerinaUtilGenerator.setPathParametersFound(true);
        return path;
    }

    /**
     * Generate the serviceUrl parameters of the client class init method.
     *
     * @param serviceUrl service Url given in the AsyncAPI file
     * @return {@link DefaultableParameterNode} when server URl is given in the AsyncAPI file
     * {@link RequiredParameterNode} when server URL is not given in the AsyncAPI file
     */
    private Node getServiceURLNode(String serviceUrl) {
        Node serviceURLNode;
        NodeList<AnnotationNode> annotationNodes = createEmptyNodeList();
        BuiltinSimpleNameReferenceNode serviceURLType = createBuiltinSimpleNameReferenceNode(null,
                createIdentifierToken("string"));
        IdentifierToken serviceURLVarName = createIdentifierToken(GeneratorConstants.SERVICE_URL);

        if (serviceUrl.equals("/")) {
            serviceURLNode = createRequiredParameterNode(annotationNodes, serviceURLType, serviceURLVarName);
        } else {
            BasicLiteralNode expression = createBasicLiteralNode(STRING_LITERAL,
                    createIdentifierToken('"' + serviceUrl + '"'));
            serviceURLNode = createDefaultableParameterNode(annotationNodes, serviceURLType,
                    serviceURLVarName, createIdentifierToken("="), expression);
        }
        return serviceURLNode;
    }

    /**
     * Generate if-else statements for the do block in client init function.
     * <pre>
     *     if config.http1Settings is ClientHttp1Settings {
     *         ClientHttp1Settings settings = check config.http1Settings.ensureType(ClientHttp1Settings);
     *         httpClientConfig.http1Settings = {...settings};
     *     }
     * </pre>
     *
     * @param fieldName name of the field
     * @param fieldType type of the field
     * @return
     */
    private IfElseStatementNode getDoBlockIfElseStatementNodes(String fieldName, String fieldType) {
        ExpressionNode expressionNode = createFieldAccessExpressionNode(
                createRequiredExpressionNode(createIdentifierToken(CONFIG)),
                createToken(DOT_TOKEN), createSimpleNameReferenceNode(createIdentifierToken(fieldName)));

        ExpressionNode condition = createBinaryExpressionNode(null,
                expressionNode,
                createToken(IS_KEYWORD),
                createIdentifierToken(fieldType)
        );

        List<StatementNode> statementNodes = new ArrayList<>();

        // httpClientConfig.http2Settings = check config.http2Settings.ensureType(http:ClientHttp2Settings);
        FieldAccessExpressionNode fieldAccessExpressionNode = createFieldAccessExpressionNode(
                createRequiredExpressionNode(createIdentifierToken(CLIENT_CONFIG)),
                createToken(DOT_TOKEN),
                createSimpleNameReferenceNode(createIdentifierToken(fieldName)));

        MethodCallExpressionNode methodCallExpressionNode = createMethodCallExpressionNode(
                createFieldAccessExpressionNode(createRequiredExpressionNode(createIdentifierToken(CONFIG)),
                        createToken(DOT_TOKEN),
                        createSimpleNameReferenceNode(createIdentifierToken(fieldName))),
                createToken(DOT_TOKEN),
                createSimpleNameReferenceNode(createIdentifierToken(ENSURE_TYPE)),
                createToken(OPEN_PAREN_TOKEN),
                createSeparatedNodeList(createPositionalArgumentNode(
                        createRequiredExpressionNode(createIdentifierToken(fieldType)))),
                createToken(CLOSE_PAREN_TOKEN));
        CheckExpressionNode checkExpressionNode = createCheckExpressionNode(null, createToken(CHECK_KEYWORD),
                methodCallExpressionNode);
        AssignmentStatementNode varAssignmentNode = createAssignmentStatementNode(fieldAccessExpressionNode,
                createToken(EQUAL_TOKEN), checkExpressionNode, createToken(SEMICOLON_TOKEN));
        statementNodes.add(varAssignmentNode);

        NodeList<StatementNode> statementList = createNodeList(statementNodes);
        BlockStatementNode ifBody = createBlockStatementNode(createToken(OPEN_BRACE_TOKEN), statementList,
                createToken(CLOSE_BRACE_TOKEN));
        return createIfElseStatementNode(createToken(IF_KEYWORD), condition,
                ifBody, null);
    }

    /**
     * Generate do block in client init function.
     *
     * @return {@link DoStatementNode}
     */
    public DoStatementNode getClientConfigDoStatementNode() {
        List<StatementNode> doStatementNodeList = new ArrayList<>();
        // ClientHttp1Settings if statement
//        {
//            ExpressionNode expressionNode = createFieldAccessExpressionNode(
//                    createRequiredExpressionNode(createIdentifierToken(CONFIG)),
//                    createToken(DOT_TOKEN), createSimpleNameReferenceNode(
//                            createIdentifierToken(CLIENT_HTTP1_SETTINGS_FIELD)));
//
//            ExpressionNode condition = createBinaryExpressionNode(null,
//                    expressionNode,
//                    createToken(IS_KEYWORD),
//                    createIdentifierToken(CLIENT_HTTP1_SETTINGS)
//            );
//
//            List<StatementNode> statementNodes = new ArrayList<>();
//
//            // ClientHttp1Settings settings = check config.http1Settings.ensureType(ClientHttp1Settings);
//            SimpleNameReferenceNode typeBindingPattern = createSimpleNameReferenceNode(
//                    createIdentifierToken(CLIENT_HTTP1_SETTINGS));
//            CaptureBindingPatternNode bindingPattern = createCaptureBindingPatternNode(
//                    createIdentifierToken(SETTINGS));
//            TypedBindingPatternNode typedBindingPatternNode = createTypedBindingPatternNode(typeBindingPattern,
//                    bindingPattern);
//            MethodCallExpressionNode methodCallExpressionNode = createMethodCallExpressionNode(
//                    createFieldAccessExpressionNode(createRequiredExpressionNode(createIdentifierToken(CONFIG)),
//                            createToken(DOT_TOKEN),
//                            createSimpleNameReferenceNode(createIdentifierToken(CLIENT_HTTP1_SETTINGS_FIELD))),
//                    createToken(DOT_TOKEN),
//                    createSimpleNameReferenceNode(createIdentifierToken(ENSURE_TYPE)),
//                    createToken(OPEN_PAREN_TOKEN),
//                    createSeparatedNodeList(createPositionalArgumentNode(
//                            createRequiredExpressionNode(createIdentifierToken(CLIENT_HTTP1_SETTINGS)))),
//                    createToken(CLOSE_PAREN_TOKEN));
//            CheckExpressionNode checkExpressionNode = createCheckExpressionNode(null, createToken(CHECK_KEYWORD),
//                    methodCallExpressionNode);
//            AssignmentStatementNode varAssignmentNode = createAssignmentStatementNode(typedBindingPatternNode,
//                    createToken(EQUAL_TOKEN), checkExpressionNode, createToken(SEMICOLON_TOKEN));
//            statementNodes.add(varAssignmentNode);
//
//            // httpClientConfig.http1Settings = {...settings};
//            FieldAccessExpressionNode fieldAccessExpressionNode = createFieldAccessExpressionNode(
//                    createRequiredExpressionNode(createIdentifierToken(WEBSOCKET_CLIENT_CONFIG)),
//                    createToken(DOT_TOKEN),
//                    createSimpleNameReferenceNode(createIdentifierToken(CLIENT_HTTP1_SETTINGS_FIELD)));
//            MappingConstructorExpressionNode mappingConstructorExpressionNode = createMappingConstructorExpressionNode(
//                    createToken(OPEN_BRACE_TOKEN),
//                    createSeparatedNodeList(
//                            createRestArgumentNode(createToken(ELLIPSIS_TOKEN),
//                                    createRequiredExpressionNode(createIdentifierToken(SETTINGS)))),
//                    createToken(CLOSE_BRACE_TOKEN));
//
//            AssignmentStatementNode fieldAssignmentNode = createAssignmentStatementNode(fieldAccessExpressionNode,
//                    createToken(EQUAL_TOKEN), mappingConstructorExpressionNode, createToken(SEMICOLON_TOKEN));
//
//            statementNodes.add(fieldAssignmentNode);
//
//            NodeList<StatementNode> statementList = createNodeList(statementNodes);
//            BlockStatementNode ifBody = createBlockStatementNode(createToken(OPEN_BRACE_TOKEN), statementList,
//                    createToken(CLOSE_BRACE_TOKEN));
//
//            IfElseStatementNode ifElseStatementNode = createIfElseStatementNode(createToken(IF_KEYWORD), condition,
//                    ifBody, null);
//            doStatementNodeList.add(ifElseStatementNode);
//        }

        doStatementNodeList.addAll(Arrays.asList(
                getDoBlockIfElseStatementNodes(SECURE_SOCKET_FIELD, SECURE_SOCKET),
//                getDoBlockIfElseStatementNodes(COOKIES_FIELD, HTTP2_SETTINGS),
//                getDoBlockIfElseStatementNodes(CACHE_CONFIG_FIELD, CACHE_CONFIG),
                getDoBlockIfElseStatementNodes(PING_PONG_HANDLER_FIELD, PING_PONG_SERVICE),

                getDoBlockIfElseStatementNodes(RETRY_CONFIG_FIELD, WEB_SOCKET_RETRY_CONFIG)));

        BlockStatementNode blockStatementNode = createBlockStatementNode(createToken(OPEN_BRACE_TOKEN),
                createNodeList(doStatementNodeList), createToken(CLOSE_BRACE_TOKEN));

        return createDoStatementNode(createToken(DO_KEYWORD),
                blockStatementNode, null);
    }

    //TODO: Use this if try to use this in choreo
    /**
     * Generate `websocketClientConfig` variable.
     * <pre>
     *        websocket:ClientConfiguration websocketClientConfig = {
     *             subProtocols: config.subProtocols,
     *             customHeaders: config.customHeaders,
     *             readTimeout: config.readTimeout,
     *             writeTimeout: config.writeTimeout,
     *             maxFrameSize: config.maxFrameSize,
     *             webSocketCompressionEnabled: config.webSocketCompressionEnabled,
     *             handShakeTimeout: config.handShakeTimeout,
     *             validation: config.validation
     *         };
     * </pre>
     *
     * @return
     */
    public VariableDeclarationNode getWebsocketClientConfigVariableNode() {
        Token comma = createToken(COMMA_TOKEN);
        NodeList<AnnotationNode> annotationNodes = createEmptyNodeList();
        // websocket:ClientConfiguration variable declaration
        SimpleNameReferenceNode typeBindingPattern = createSimpleNameReferenceNode(
                createIdentifierToken("websocket:ClientConfiguration"));
        CaptureBindingPatternNode bindingPattern = createCaptureBindingPatternNode(
                createIdentifierToken(CLIENT_CONFIG));
        TypedBindingPatternNode typedBindingPatternNode = createTypedBindingPatternNode(typeBindingPattern,
                bindingPattern);

        List<Node> argumentsList = new ArrayList<>();

        if (isHttpOROAuth() && !isHttpApiKey()) {
            ExpressionNode authValExp = createFieldAccessExpressionNode(
                    createSimpleNameReferenceNode(createIdentifierToken(CONFIG)),
                    createToken(DOT_TOKEN), createSimpleNameReferenceNode(createIdentifierToken(AUTH)));
            SpecificFieldNode authField = createSpecificFieldNode(null,
                    createIdentifierToken(AUTH),
                    createToken(COLON_TOKEN), authValExp);
            argumentsList.add(authField);
            argumentsList.add(comma);
        }

        // create subProtocols field
        ExpressionNode subProtocolsValExp = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(CONFIG)),
                createToken(DOT_TOKEN), createSimpleNameReferenceNode(createIdentifierToken("subProtocols")));
        SpecificFieldNode subProtocolsField = createSpecificFieldNode(null,
                createIdentifierToken("subProtocols"),
                createToken(COLON_TOKEN), subProtocolsValExp);
        argumentsList.add(subProtocolsField);
        argumentsList.add(comma);
        // create customHeaders field
        ExpressionNode customHeadersValExp = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(CONFIG)),
                createToken(DOT_TOKEN), createSimpleNameReferenceNode(createIdentifierToken("customHeaders")));
        SpecificFieldNode customHeadersField = createSpecificFieldNode(null,
                createIdentifierToken("customHeaders"),
                createToken(COLON_TOKEN), customHeadersValExp);
        argumentsList.add(customHeadersField);
        argumentsList.add(comma);

        // create readTimeout field
        ExpressionNode readTimeoutValExp = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(CONFIG)),
                createToken(DOT_TOKEN), createSimpleNameReferenceNode(createIdentifierToken("readTimeout")));
        SpecificFieldNode readTimeoutField = createSpecificFieldNode(null,
                createIdentifierToken("readTimeout"),
                createToken(COLON_TOKEN), readTimeoutValExp);
        argumentsList.add(readTimeoutField);
        argumentsList.add(comma);

        // create writeTimeout field
        ExpressionNode writeTimeoutValExp = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(CONFIG)),
                createToken(DOT_TOKEN), createSimpleNameReferenceNode(createIdentifierToken("writeTimeout")));
        SpecificFieldNode writeTimeoutField = createSpecificFieldNode(null,
                createIdentifierToken("writeTimeout"),
                createToken(COLON_TOKEN), writeTimeoutValExp);
        argumentsList.add(writeTimeoutField);
        argumentsList.add(comma);

        // create maxFrameSize field
        ExpressionNode maxFrameSizeValExp = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(CONFIG)),
                createToken(DOT_TOKEN), createSimpleNameReferenceNode(createIdentifierToken("maxFrameSize")));
        SpecificFieldNode maxFrameSizeField = createSpecificFieldNode(null,
                createIdentifierToken("maxFrameSize"),
                createToken(COLON_TOKEN), maxFrameSizeValExp);
        argumentsList.add(maxFrameSizeField);
        argumentsList.add(comma);

        // create webSocketCompressionEnabled field
        ExpressionNode webSocketCompressionEnabledValExp = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(CONFIG)),
                createToken(DOT_TOKEN), createSimpleNameReferenceNode(createIdentifierToken(
                        "webSocketCompressionEnabled")));
        SpecificFieldNode webSocketCompressionEnabledField = createSpecificFieldNode(null,
                createIdentifierToken("webSocketCompressionEnabled"),
                createToken(COLON_TOKEN), webSocketCompressionEnabledValExp);
        argumentsList.add(webSocketCompressionEnabledField);
        argumentsList.add(comma);

        // create handleShakeTimeout field
        ExpressionNode handleShakeTimeoutValExp = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(CONFIG)),
                createToken(DOT_TOKEN), createSimpleNameReferenceNode(createIdentifierToken("handleShakeTimeout")));
        SpecificFieldNode handleShakeTimeoutField = createSpecificFieldNode(null,
                createIdentifierToken("handleShakeTimeout"),
                createToken(COLON_TOKEN), handleShakeTimeoutValExp);
        argumentsList.add(handleShakeTimeoutField);
        argumentsList.add(comma);

        // create validation field
        ExpressionNode validationValExp = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(CONFIG)),
                createToken(DOT_TOKEN), createSimpleNameReferenceNode(createIdentifierToken("validation")));
        SpecificFieldNode validationField = createSpecificFieldNode(null,
                createIdentifierToken("validation"),
                createToken(COLON_TOKEN), validationValExp);
        argumentsList.add(validationField);

        SeparatedNodeList<MappingFieldNode> arguments = createSeparatedNodeList(argumentsList);
        MappingConstructorExpressionNode mappingConstructorExpressionNode =
                createMappingConstructorExpressionNode(createToken(OPEN_BRACE_TOKEN),
                        arguments, createToken(CLOSE_BRACE_TOKEN));
        return createVariableDeclarationNode(annotationNodes, null, typedBindingPatternNode,
                createToken(EQUAL_TOKEN), mappingConstructorExpressionNode, createToken(SEMICOLON_TOKEN));
    }

    /**
     * Generate http:client initialization node.
     * <pre>
     *     websocket:Client websocketEp = check new (serviceUrl, clientConfig);
     * </pre>
     *
     * @return {@link VariableDeclarationNode}   Syntax tree node of client initialization
     */
    public VariableDeclarationNode getClientInitializationNode() {

        NodeList<AnnotationNode> annotationNodes = createEmptyNodeList();
        // http:Client variable declaration
        BuiltinSimpleNameReferenceNode typeBindingPattern = createBuiltinSimpleNameReferenceNode(null,
                createIdentifierToken("websocket:Client"));
        CaptureBindingPatternNode bindingPattern = createCaptureBindingPatternNode(
                createIdentifierToken("websocketEp"));
        TypedBindingPatternNode typedBindingPatternNode = createTypedBindingPatternNode(typeBindingPattern,
                bindingPattern);

        // Expression node
        List<Node> argumentsList = new ArrayList<>();
        PositionalArgumentNode positionalArgumentNode01 = createPositionalArgumentNode(createSimpleNameReferenceNode(
                createIdentifierToken(GeneratorConstants.SERVICE_URL)));
        argumentsList.add(positionalArgumentNode01);
        Token comma1 = createIdentifierToken(",");

        PositionalArgumentNode positionalArgumentNode02 = createPositionalArgumentNode(createSimpleNameReferenceNode(
                createIdentifierToken(CLIENT_CONFIG)));
        argumentsList.add(comma1);
        argumentsList.add(positionalArgumentNode02);

        SeparatedNodeList<FunctionArgumentNode> arguments = createSeparatedNodeList(argumentsList);
        Token closeParenArg = createToken(CLOSE_PAREN_TOKEN);
        ParenthesizedArgList parenthesizedArgList = createParenthesizedArgList(createToken(OPEN_PAREN_TOKEN), arguments,
                closeParenArg);
        ImplicitNewExpressionNode expressionNode = createImplicitNewExpressionNode(createToken(NEW_KEYWORD),
                parenthesizedArgList);
        CheckExpressionNode initializer = createCheckExpressionNode(null, createToken(CHECK_KEYWORD),
                expressionNode);
        return createVariableDeclarationNode(annotationNodes,
                null, typedBindingPatternNode, createToken(EQUAL_TOKEN), initializer,
                createToken(SEMICOLON_TOKEN));
    }

    /**
     * Generate assignment nodes for api key map assignment {@code self.apiKeyConfig=apiKeyConfig.cloneReadOnly();}.
     *
     * @return {@link AssignmentStatementNode} syntax tree assignment statement node.
     */
    public AssignmentStatementNode getApiKeyAssignmentNode() {

        if (httpApiKey) {
            FieldAccessExpressionNode varRefApiKey = createFieldAccessExpressionNode(
                    createSimpleNameReferenceNode(createIdentifierToken(SELF)), createToken(DOT_TOKEN),
                    createSimpleNameReferenceNode(createIdentifierToken(API_KEY_CONFIG_PARAM)));
            ExpressionNode fieldAccessExpressionNode = createRequiredExpressionNode(
                    createIdentifierToken(API_KEY_CONFIG_PARAM));
            ExpressionNode methodCallExpressionNode = createMethodCallExpressionNode(
                    fieldAccessExpressionNode, createToken(DOT_TOKEN),
                    createSimpleNameReferenceNode(createIdentifierToken("cloneReadOnly")),
                    createToken(OPEN_PAREN_TOKEN), createSeparatedNodeList(), createToken(CLOSE_PAREN_TOKEN));
            return createAssignmentStatementNode(varRefApiKey,
                    createToken(EQUAL_TOKEN), methodCallExpressionNode, createToken(SEMICOLON_TOKEN));
        }
        return null;
    }

    /**
     * Returns fields in ClientConfig record.
     *          # Configurations related to client authentication
     *          websocket:BearerTokenConfig|websocket:OAuth2RefreshTokenGrantConfig auth;
     *          # Negotiable sub protocols of the client
     *          string[] subProtocols = [];
     *          # Custom headers, which should be sent to the server
     *          map<string> customHeaders = {};
     *          # Read timeout (in seconds) of the client
     *          decimal readTimeout = -1;
     *          # Write timeout (in seconds) of the client
     *          decimal writeTimeout = -1;
     *          # SSL/TLS-related options
     *          websocket:ClientSecureSocket? secureSocket = ();
     *          # The maximum payload size of a WebSocket frame in bytes
     *          int maxFrameSize = 65536;
     *          # Enable support for compression in the WebSocket
     *          boolean webSocketCompressionEnabled = true;
     *          # Time (in seconds) that a connection waits to get the response of the WebSocket handshake.
     *          decimal handShakeTimeout = 300;
     *          # An Array of http:Cookie
     *          http:Cookie[] cookies?;
     *          # A service to handle the ping/pong frames.
     *          PingPongService pingPongHandler?;
     *          # Configurations associated with retrying
     *          websocket:WebSocketRetryConfig retryConfig? = ();
     *          Enable/disable constraint validation
     *          boolean validation = true;
     */
    private List<Node> getClientConfigRecordFields() {

        List<Node> recordFieldNodes = new ArrayList<>();
        Token semicolonToken = createToken(SEMICOLON_TOKEN);
        Token equalToken = createToken(EQUAL_TOKEN);
        Token questionMarkToken = createToken(QUESTION_MARK_TOKEN);

        // add auth field
        if (isHttpOROAuth() && !isHttpApiKey()) {
            MetadataNode authMetadataNode = getMetadataNode("Configurations related to client authentication");
            IdentifierToken authFieldName = AbstractNodeFactory.createIdentifierToken(escapeIdentifier(
                    AUTH));
            TypeDescriptorNode authFieldTypeNode =
                    createSimpleNameReferenceNode(createIdentifierToken(getAuthFieldTypeName()));
            RecordFieldNode authFieldNode = NodeFactory.createRecordFieldNode(authMetadataNode, null,
                    authFieldTypeNode, authFieldName, null, semicolonToken);
            recordFieldNodes.add(authFieldNode);
        } else if (isHttpOROAuth() && isHttpApiKey()) {
            MetadataNode authMetadataNode = getMetadataNode(
                    "Provides Auth configurations needed when communicating with a remote Websocket " +
                            "service endpoint.");
            IdentifierToken authFieldName = AbstractNodeFactory.createIdentifierToken(escapeIdentifier(
                    AUTH));
            TypeDescriptorNode unionTypeDescriptor = createUnionTypeDescriptorNode(
                    createSimpleNameReferenceNode(createIdentifierToken(getAuthFieldTypeName())),
                    createToken(PIPE_TOKEN),
                    createSimpleNameReferenceNode(createIdentifierToken(API_KEYS_CONFIG)));
            RecordFieldNode authFieldNode = NodeFactory.createRecordFieldNode(authMetadataNode, null,
                    unionTypeDescriptor, authFieldName, null, semicolonToken);
            recordFieldNodes.add(authFieldNode);
        }

        // add subProtocols field
        MetadataNode subProtocolsMetadata = getMetadataNode("Negotiable sub protocols of the client");
        IdentifierToken subProtocolsFieldName = createIdentifierToken("subProtocols");
        NodeList<ArrayDimensionNode> arrayDimensions = NodeFactory.createEmptyNodeList();
        ArrayDimensionNode arrayDimension = NodeFactory.createArrayDimensionNode(
                createToken(SyntaxKind.OPEN_BRACKET_TOKEN), null,
                createToken(SyntaxKind.CLOSE_BRACKET_TOKEN));
        arrayDimensions = arrayDimensions.add(arrayDimension);
        TypeDescriptorNode subProtocolsMemberType = createSimpleNameReferenceNode(createToken(STRING_KEYWORD));
        ArrayTypeDescriptorNode subProtocolsFieldType = createArrayTypeDescriptorNode(subProtocolsMemberType
                , arrayDimensions);
        RequiredExpressionNode subProtocolsExpression =
                createRequiredExpressionNode(createIdentifierToken("[]"));
        RecordFieldWithDefaultValueNode subProtocolsFieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                subProtocolsMetadata, null, subProtocolsFieldType, subProtocolsFieldName,
                equalToken, subProtocolsExpression , semicolonToken);
        recordFieldNodes.add(subProtocolsFieldNode);

        // add customHeaders field
        TypeDescriptorNode customHeadersMapParamType = createSimpleNameReferenceNode(createToken(STRING_KEYWORD));
        TypeParameterNode customHeadersTypeParamsNode=createTypeParameterNode(createToken(LT_TOKEN),
                customHeadersMapParamType,createToken(GT_TOKEN));
       MapTypeDescriptorNode customHeadersFieldType= createMapTypeDescriptorNode(createToken(MAP_KEYWORD),
               customHeadersTypeParamsNode);
        MetadataNode customHeadersMetadata = getMetadataNode("Custom headers, " +
                "which should be sent to the server");
        IdentifierToken customHeadersFieldName = createIdentifierToken("customHeaders");
        RequiredExpressionNode customHeadersExpression =
                createRequiredExpressionNode(createIdentifierToken("{}"));
        RecordFieldWithDefaultValueNode customHeadersFieldNode = createRecordFieldWithDefaultValueNode(
                customHeadersMetadata, null, customHeadersFieldType, customHeadersFieldName,
                equalToken,customHeadersExpression, semicolonToken);
        recordFieldNodes.add(customHeadersFieldNode);

        // add readTimeout field
        MetadataNode readTimeOutMetadata = getMetadataNode("Read timeout (in seconds) of the client");
        TypeDescriptorNode readTimeOutFieldType =
                createSimpleNameReferenceNode(createToken(DECIMAL_KEYWORD));
        IdentifierToken readTimeoutFieldName = createIdentifierToken("readTimeout");
        ExpressionNode readTimeOutDecimalLiteralNode = createRequiredExpressionNode(createIdentifierToken("-1"));
        RecordFieldWithDefaultValueNode readTimeoutFieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                readTimeOutMetadata, null, readTimeOutFieldType, readTimeoutFieldName,
                equalToken, readTimeOutDecimalLiteralNode,semicolonToken);
        recordFieldNodes.add(readTimeoutFieldNode);

        // add writeTimeout field
        MetadataNode writeTimeOutMetadata = getMetadataNode("Write timeout (in seconds) of the client");
        TypeDescriptorNode writeTimeOutFieldType =
                createSimpleNameReferenceNode(createToken(DECIMAL_KEYWORD));
        IdentifierToken writeTimeoutFieldName = createIdentifierToken("writeTimeout");
        ExpressionNode writeTimeOutDecimalLiteralNode = createRequiredExpressionNode(createIdentifierToken("-1"));
        RecordFieldWithDefaultValueNode writeTimeoutFieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                writeTimeOutMetadata, null, writeTimeOutFieldType, writeTimeoutFieldName,
                equalToken, writeTimeOutDecimalLiteralNode,semicolonToken);
        recordFieldNodes.add(writeTimeoutFieldNode);


        // add secureSocket field
        MetadataNode secureSocketMetadata = getMetadataNode("SSL/TLS-related options");
        IdentifierToken secureSocketFieldName = AbstractNodeFactory.createIdentifierToken(SECURE_SOCKET_FIELD);
        TypeDescriptorNode secureSocketfieldType = createOptionalTypeDescriptorNode(createIdentifierToken("websocket:ClientSecureSocket"),questionMarkToken);
        RecordFieldWithDefaultValueNode secureSocketFieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                secureSocketMetadata, null, secureSocketfieldType, secureSocketFieldName,
                equalToken,createRequiredExpressionNode(createIdentifierToken("()")), semicolonToken);
        recordFieldNodes.add(secureSocketFieldNode);


        // add maxFrameSize field
        MetadataNode maxFrameSizeMetadata = getMetadataNode(" The maximum payload size of a WebSocket" +
                " frame in bytes");
        TypeDescriptorNode maxFrameSizeFieldType =
                createSimpleNameReferenceNode(createToken(INT_KEYWORD));
        IdentifierToken maxFrameSizeFieldName = createIdentifierToken("maxFrameSize");
        ExpressionNode maxFrameSizeDecimalLiteralNode = createRequiredExpressionNode(
                createIdentifierToken("65536"));
        RecordFieldWithDefaultValueNode maxFrameSizeFieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                maxFrameSizeMetadata, null, maxFrameSizeFieldType, maxFrameSizeFieldName,
                equalToken, maxFrameSizeDecimalLiteralNode,semicolonToken);
        recordFieldNodes.add(maxFrameSizeFieldNode);

        // add webSocketCompressionEnabled field
        MetadataNode webSocketCompressionEnabledMetadata = getMetadataNode("Enable support for compression in"
                + " the WebSocket");
        TypeDescriptorNode webSocketCompressionEnabledFieldType =
                createSimpleNameReferenceNode(createToken(BOOLEAN_KEYWORD));
        IdentifierToken webSocketCompressionEnabledFieldName = createIdentifierToken(
                "webSocketCompressionEnabled");
        RecordFieldWithDefaultValueNode webSocketCompressionEnabledFieldNode = NodeFactory.
                createRecordFieldWithDefaultValueNode(
                webSocketCompressionEnabledMetadata, null, webSocketCompressionEnabledFieldType,
                        webSocketCompressionEnabledFieldName,
                equalToken, createRequiredExpressionNode(
                                createToken(TRUE_KEYWORD)),semicolonToken);
        recordFieldNodes.add(webSocketCompressionEnabledFieldNode);


        // add handShakeTimeout field
        MetadataNode handShakeTimeoutMetadata = getMetadataNode("Time (in seconds) that a connection waits to " +
                "get the response of the WebSocket handshake");
        TypeDescriptorNode handShakeTimeoutFieldType =
                createSimpleNameReferenceNode(createToken(DECIMAL_KEYWORD));
        IdentifierToken handShakeTimeoutFieldName = createIdentifierToken("handShakeTimeout");
        ExpressionNode handShakeTimeoutDecimalLiteralNode = createRequiredExpressionNode(
                createIdentifierToken("300"));
        RecordFieldWithDefaultValueNode handShakeTimeoutFieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                handShakeTimeoutMetadata, null, handShakeTimeoutFieldType, handShakeTimeoutFieldName,
                equalToken, handShakeTimeoutDecimalLiteralNode,semicolonToken);
        recordFieldNodes.add(handShakeTimeoutFieldNode);


        // add cookies field
        MetadataNode cookiesMetadata = getMetadataNode("An Array of http:Cookie");
        IdentifierToken cookiesFieldName = createIdentifierToken("cookies");
        NodeList<ArrayDimensionNode> cookiesArrayDimensions = NodeFactory.createEmptyNodeList();
        ArrayDimensionNode cookiesArrayDimension = NodeFactory.createArrayDimensionNode(
                createToken(SyntaxKind.OPEN_BRACKET_TOKEN), null,
                createToken(SyntaxKind.CLOSE_BRACKET_TOKEN));
        cookiesArrayDimensions = cookiesArrayDimensions.add(cookiesArrayDimension);
        TypeDescriptorNode cookiesArrayMemberType = createSimpleNameReferenceNode(createIdentifierToken
                ("http:Cookie"));
        ArrayTypeDescriptorNode cookiesFieldType = createArrayTypeDescriptorNode(cookiesArrayMemberType
                , cookiesArrayDimensions);
        RequiredExpressionNode cookiesExpression =
                createRequiredExpressionNode(createIdentifierToken("[]"));
        RecordFieldNode cookiesFieldNode = NodeFactory.createRecordFieldNode(
                cookiesMetadata, null, cookiesFieldType, cookiesFieldName,
                questionMarkToken,  semicolonToken);
        recordFieldNodes.add(cookiesFieldNode);


        // add pingPongHandler field
        MetadataNode pingPongHandlerMetadata = getMetadataNode("A service to handle the ping/pong frames");
        IdentifierToken pingPongHandlerFieldName = AbstractNodeFactory.createIdentifierToken(PING_PONG_SERVICE_FIELD);
        TypeDescriptorNode pingPongHandlerFieldType = createSimpleNameReferenceNode(
                createIdentifierToken("websocket:PingPongService"));
        RecordFieldNode pingPongHandlerFieldNode = NodeFactory.createRecordFieldNode(
                pingPongHandlerMetadata, null, pingPongHandlerFieldType, pingPongHandlerFieldName,
                questionMarkToken, semicolonToken);
        recordFieldNodes.add(pingPongHandlerFieldNode);

        // add retryConfig field
        MetadataNode retryConfigMetadata = getMetadataNode("Configurations associated with retrying");
        IdentifierToken retryConfigFieldName = AbstractNodeFactory.createIdentifierToken("retryConfig");
        TypeDescriptorNode returConfigFieldType = createOptionalTypeDescriptorNode(
                createIdentifierToken("websocket:WebSocketRetryConfig"),questionMarkToken);
        RecordFieldWithDefaultValueNode retryConfigFieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                retryConfigMetadata, null, returConfigFieldType, retryConfigFieldName,
                equalToken,createRequiredExpressionNode(createIdentifierToken("()")), semicolonToken);
        recordFieldNodes.add(retryConfigFieldNode);


        // add validation field
        MetadataNode validationMetadata = getMetadataNode("Enable/disable constraint validation");
        IdentifierToken validationFieldName = AbstractNodeFactory.createIdentifierToken(VALIDATION);
        TypeDescriptorNode validationFieldType = createSimpleNameReferenceNode(createIdentifierToken(BOOLEAN));
        RecordFieldWithDefaultValueNode validateFieldNode = NodeFactory.createRecordFieldWithDefaultValueNode(
                validationMetadata, null, validationFieldType, validationFieldName,
                equalToken, createRequiredExpressionNode(createToken(TRUE_KEYWORD)), semicolonToken);
        recordFieldNodes.add(validateFieldNode);

        return recordFieldNodes;
    }

    /**
     * Generate statements for init function when combination of ApiKeys and HTTP/OAuth authentication is used.
     *
     * <pre>
     *     if config.auth is ApiKeysConfig {
     *         self.apiKeyConfig = (<ApiKeysConfig>config.auth).cloneReadOnly();
     *     } else {
     *         config.auth = <http:BearerTokenConfig>config.auth;
     *         self.apiKeyConfig = ();
     *     }
     * </pre>
     */
    public IfElseStatementNode handleInitForMixOfApiKeyAndHTTPOrOAuth() {
        List<StatementNode> apiKeyConfigAssignmentNodes = new ArrayList<>();

        // `self.apiKeyConfig = (<ApiKeysConfig>config.auth).cloneReadOnly();`
        FieldAccessExpressionNode apiKeyConfigRef = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(SELF)), createToken(DOT_TOKEN),
                createSimpleNameReferenceNode(createIdentifierToken(API_KEY_CONFIG_PARAM)));
        SimpleNameReferenceNode apiKeyConfigExpr = createSimpleNameReferenceNode(createIdentifierToken(
                "(<ApiKeysConfig>config.auth).cloneReadOnly()"));
        AssignmentStatementNode apiKeyConfigAssignmentStatementNode = createAssignmentStatementNode(apiKeyConfigRef,
                createToken(EQUAL_TOKEN), apiKeyConfigExpr, createToken(SEMICOLON_TOKEN));
        apiKeyConfigAssignmentNodes.add(apiKeyConfigAssignmentStatementNode);
        NodeList<StatementNode> statementList = createNodeList(apiKeyConfigAssignmentNodes);
        BlockStatementNode ifBody = createBlockStatementNode(createToken(OPEN_BRACE_TOKEN), statementList,
                createToken(CLOSE_BRACE_TOKEN));

        List<StatementNode> clientConfigAssignmentNodes = new ArrayList<>();

        // config.auth = <http:BearerTokenConfig>config.auth;
        FieldAccessExpressionNode clientConfigAuthRef = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(CONFIG)), createToken(DOT_TOKEN),
                createSimpleNameReferenceNode(createIdentifierToken(AUTH)));
        SimpleNameReferenceNode clientConfigExpr = createSimpleNameReferenceNode(
                createIdentifierToken("<" + getAuthFieldTypeName() +
                        ">" + CONFIG + DOT_TOKEN.stringValue() + AUTH));
        AssignmentStatementNode httpClientAuthConfigAssignment = createAssignmentStatementNode(clientConfigAuthRef,
                createToken(EQUAL_TOKEN), clientConfigExpr, createToken(SEMICOLON_TOKEN));
        clientConfigAssignmentNodes.add(httpClientAuthConfigAssignment);

        // `self.apiKeyConfig = ();`
        FieldAccessExpressionNode apiKeyConfigNilRef = createFieldAccessExpressionNode(
                createSimpleNameReferenceNode(createIdentifierToken(SELF)), createToken(DOT_TOKEN),
                createSimpleNameReferenceNode(createIdentifierToken(API_KEY_CONFIG_PARAM)));
        SimpleNameReferenceNode apiKeyConfigNilExpr = createSimpleNameReferenceNode(
                createIdentifierToken("()"));
        AssignmentStatementNode apiKeyConfigNilAssignment = createAssignmentStatementNode(apiKeyConfigNilRef,
                createToken(EQUAL_TOKEN), apiKeyConfigNilExpr, createToken(SEMICOLON_TOKEN));
        clientConfigAssignmentNodes.add(apiKeyConfigNilAssignment);

        NodeList<StatementNode> elseBodyNodeList = createNodeList(clientConfigAssignmentNodes);
        StatementNode elseBodyStatement = createBlockStatementNode(createToken(OPEN_BRACE_TOKEN), elseBodyNodeList,
                createToken(CLOSE_BRACE_TOKEN));
        ElseBlockNode elseBody = createElseBlockNode(createToken(ELSE_KEYWORD), elseBodyStatement);

        ExpressionNode condition = createBinaryExpressionNode(null,
                createIdentifierToken(CONFIG + DOT_TOKEN.stringValue() + AUTH),
                createToken(IS_KEYWORD),
                createIdentifierToken(API_KEYS_CONFIG)
        );
        return createIfElseStatementNode(createToken(IF_KEYWORD), condition,
                ifBody, elseBody);
    }




    private MetadataNode getMetadataNode(String comment) {

        List<Node> docs = new ArrayList<>(DocCommentsGenerator.createAPIDescriptionDoc(comment, false));
        MarkdownDocumentationNode authDocumentationNode = createMarkdownDocumentationNode(
                createNodeList(docs));
        return createMetadataNode(authDocumentationNode, createEmptyNodeList());
    }

    private MetadataNode getMetadataNode(String comment, List<AnnotationNode> annotationNodes) {
        List<Node> docs = new ArrayList<>(DocCommentsGenerator.createAPIDescriptionDoc(comment, false));
        MarkdownDocumentationNode authDocumentationNode = createMarkdownDocumentationNode(
                createNodeList(docs));
        return createMetadataNode(authDocumentationNode, createNodeList(annotationNodes));
    }

    /**
     * Travers through the security schemas of the given async api spec.
     * Store api key names which needs to send in the query string and as a request header separately.
     *
     * @param securitySchemeMap Map of security schemas of the given async api spec
     */
    public void setAuthTypes(Map<String, SecurityScheme> securitySchemeMap) throws
            BallerinaAsyncApiException {

        for (Map.Entry<String, SecurityScheme> securitySchemeEntry : securitySchemeMap.entrySet()) {
            AsyncApi25SecuritySchemeImpl securitySchemaValue = (AsyncApi25SecuritySchemeImpl)securitySchemeEntry.getValue();
            if (securitySchemaValue != null && securitySchemaValue.getType() != null) {
                String schemaType = securitySchemaValue.getType().toLowerCase(Locale.getDefault());

                //TODO: Here apiKey and userPassword have to be map
                switch (schemaType) {
                    case HTTP:
                        httpOROAuth = true;
                        String scheme = securitySchemaValue.getScheme();
                        if (scheme.equals(BASIC)) {
                            authTypes.add(BASIC);
                        } else if (scheme.equals(BEARER)) {
                            authTypes.add(BEARER);
                        }
                        break;
                    case OAUTH2:
                        httpOROAuth = true;
                        if (securitySchemaValue.getFlows().getClientCredentials() != null) {
                            if (securitySchemaValue.getFlows().getClientCredentials().getTokenUrl() != null) {
                                clientCredGrantTokenUrl = securitySchemaValue.getFlows().getClientCredentials().getTokenUrl();
                            }
                            authTypes.add(CLIENT_CRED);
                        }
                        if (securitySchemaValue.getFlows().getPassword() != null) {
                            if (securitySchemaValue.getFlows().getPassword().getTokenUrl() != null) {

                                passwordGrantTokenUrl = securitySchemaValue.getFlows().getPassword().getTokenUrl();
                            }
                            authTypes.add(PASSWORD);
                        }
                        if (securitySchemaValue.getFlows().getAuthorizationCode() != null) {
                            if (securitySchemaValue.getFlows().getAuthorizationCode().getTokenUrl() != null) {
                                refreshTokenUrl = securitySchemaValue.getFlows().getAuthorizationCode().getTokenUrl();
                            }
                            authTypes.addAll(Arrays.asList(BEARER, REFRESH_TOKEN));
                        }
                        if (securitySchemaValue.getFlows().getImplicit() != null) {
                            authTypes.add(BEARER);
                        }
                        break;
                    case HTTP_API_KEY:
                        httpApiKey = true;
                        String apiKeyType = securitySchemaValue.getIn().toLowerCase(Locale.getDefault());
                        authTypes.add(HTTP_API_KEY);
                        setApiKeysConfigRecordFields(securitySchemaValue);
                        switch (apiKeyType) {
                            case "query":
                                queryApiKeyNameList.put(securitySchemeEntry.getKey(), securitySchemaValue.getName());
                                break;
                            case "header":
                                headerApiKeyNameList.put(securitySchemeEntry.getKey(), securitySchemaValue.getName());
                                break;
                            default:
                                break;
                        }
                        break;

                    case USER_PASSWORD:
                        throw new BallerinaAsyncApiException("userPassword type security schema doesn't support yet");
                    case API_KEY:
                        throw new BallerinaAsyncApiException("apiKey type security schema doesn't support yet");


                }
            }
        }
        if (!(httpApiKey || httpOROAuth)) {
            throw new BallerinaAsyncApiException("Ballerina unsupported type of security schema");
        }
    }

    /**
     * Returns fields in ApiKeysConfig record.
     * <pre>
     *     # API key related to connector authentication
     *     string apiKey;
     * </pre>
     */
    private void setApiKeysConfigRecordFields(AsyncApi25SecuritySchemeImpl securityScheme) {

        MetadataNode metadataNode = null;
        if (securityScheme.getDescription() != null) {
//            List<AnnotationNode> annotationNodes = Collections.singletonList(getDisplayAnnotationForPasswordField());
//            metadataNode = getMetadataNode(securityScheme.getDescription(), annotationNodes);
            metadataNode = getMetadataNode(securityScheme.getDescription());

        }
        TypeDescriptorNode stringTypeDesc = createSimpleNameReferenceNode(createToken(STRING_KEYWORD));
        IdentifierToken apiKeyName = createIdentifierToken(getValidName(securityScheme.getName(), false));
        apiKeysConfigRecordFields.add(createRecordFieldNode(metadataNode, null, stringTypeDesc,
                apiKeyName, null, createToken(SEMICOLON_TOKEN)));
    }

    /**
     * Travers through the authTypes and generate the field type name of auth field in ClientConfig record.

     * @return {@link String}   Field type name of auth field
     * Ex: {@code http:BearerTokenConfig|http:OAuth2RefreshTokenGrantConfig}
     */
    private String getAuthFieldTypeName() {
        Set<String> httpFieldTypeNames = new HashSet<>();
        for (String authType : authTypes) {
            switch (authType) {
                case BEARER:
                    httpFieldTypeNames.add(GeneratorConstants.AuthConfigTypes.BEARER.getValue());
                    break;
                case BASIC:
                    httpFieldTypeNames.add(GeneratorConstants.AuthConfigTypes.BASIC.getValue());
                    break;
                case CLIENT_CRED:
                    // Previous version of swagger parser returns null value, when it has an
                    // empty string as a value (ex: tokenURL: ""). Latest version `2.0.30` version
                    // returns empty string as it is. Therefore, we have to check both null check and empty string
                    // check.
                    if (clientCredGrantTokenUrl != null && !clientCredGrantTokenUrl.isBlank()) {
                        httpFieldTypeNames.add(GeneratorConstants.AuthConfigTypes.CUSTOM_CLIENT_CREDENTIAL.getValue());
                    } else {
                        httpFieldTypeNames.add(GeneratorConstants.AuthConfigTypes.CLIENT_CREDENTIAL.getValue());
                    }
                    break;
                case PASSWORD:
                    if (passwordGrantTokenUrl != null && !passwordGrantTokenUrl.isBlank()) {
                        httpFieldTypeNames.add(GeneratorConstants.AuthConfigTypes.CUSTOM_PASSWORD.getValue());
                    } else {
                        httpFieldTypeNames.add(GeneratorConstants.AuthConfigTypes.PASSWORD.getValue());
                    }
                    break;
                case REFRESH_TOKEN:
                    if (refreshTokenUrl != null && !refreshTokenUrl.isBlank()) {
                        httpFieldTypeNames.add(GeneratorConstants.AuthConfigTypes.CUSTOM_REFRESH_TOKEN.getValue());
                    } else {
                        httpFieldTypeNames.add(GeneratorConstants.AuthConfigTypes.REFRESH_TOKEN.getValue());
                    }
                    break;
                default:
                    break;
            }
        }
        return buildConfigRecordFieldTypes(httpFieldTypeNames).toString();
    }

    /**
     * This method is used concat the config record authConfig field type.
     *
     * @param fieldtypes Type name set from {@link #setAuthTypes(Map)} method.
     * @return {@link String}   Pipe concatenated list of type names
     */
    private StringBuilder buildConfigRecordFieldTypes(Set<String> fieldtypes) {
        StringBuilder httpAuthFieldTypes = new StringBuilder();
        if (!fieldtypes.isEmpty()) {
            for (String fieldType : fieldtypes) {
                if (httpAuthFieldTypes.length() != 0) {
                    httpAuthFieldTypes.append("|").append(fieldType);
                } else {
                    httpAuthFieldTypes.append(fieldType);
                }
            }
        }
        return httpAuthFieldTypes;
    }
}
