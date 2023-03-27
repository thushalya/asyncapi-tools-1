import ballerina/websocket;
import ballerina/http;

listener websocket:Listener helloEp = new (9090);

public type Subscribe record{
    int id;
    string event;
};

@websocket:ServiceConfig{dispatcherKey: "event"}
service /payloadV on helloEp {
    resource function get v1(@http:Header {} string[] XClient) returns websocket:Service|websocket:UpgradeError  {
           return new ChatServer();
    }

}

service class ChatServer{
    *websocket:Service;

    remote function onSubscribe(websocket:Caller caller, Subscribe message) returns int {
        return 5;
    }

}

