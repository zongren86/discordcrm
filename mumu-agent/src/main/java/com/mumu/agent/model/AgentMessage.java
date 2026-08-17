package com.mumu.agent.model;

import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class AgentMessage {
    
    private String type;
    private String taskId;
    private String message;
    private Object data;
    private Map<String, Object> params;
    private Instant timestamp = Instant.now();
    
    public static AgentMessage of(String type, String taskId, Object data) {
        AgentMessage msg = new AgentMessage();
        msg.setType(type);
        msg.setTaskId(taskId);
        msg.setData(data);
        return msg;
    }
    
    public static AgentMessage status(String type, String message) {
        AgentMessage msg = new AgentMessage();
        msg.setType(type);
        msg.setMessage(message);
        return msg;
    }
}
