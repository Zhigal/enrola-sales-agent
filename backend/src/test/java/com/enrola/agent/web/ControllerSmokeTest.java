package com.enrola.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.agent.DbTest;
import com.enrola.agent.engine.LlmResponse;
import com.enrola.agent.engine.ScriptedLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ControllerSmokeTest extends DbTest {

    private static final String TURN = """
        {"message":"Are you looking to save money or improve your cover?","stage":"SITUATION",
         "goalMet":false,"unsubscribed":false,"endConversation":false,"endReason":"NONE",
         "objectionRaised":false}
        """;

    @Autowired MockMvc mvc;
    @Autowired ScriptedLlmClient llm;

    @Test
    void listsLeads() throws Exception {
        mvc.perform(get("/api/leads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customerId").value("comparato"));
    }

    @Test
    void startsSendsFetchesAndResets() throws Exception {
        llm.reset();
        llm.queue(LlmResponse.message(TURN), LlmResponse.message(TURN), LlmResponse.message(TURN));

        var body = mvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leadId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.messages[0].direction").value("OUTBOUND"))
                .andExpect(jsonPath("$.messages[0].structuredOutput.stage").value("SITUATION"))
                .andExpect(jsonPath("$.messages[0].characters").isNumber())
                .andReturn().getResponse().getContentAsString();
        var id = com.jayway.jsonpath.JsonPath.read(body, "$.id").toString();

        mvc.perform(post("/api/conversations/" + id + "/messages")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"both\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(3));

        mvc.perform(get("/api/conversations/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smsCharLimit").value(320));

        mvc.perform(post("/api/conversations/" + id + "/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(1));
    }

    @Test
    void unknownConversationIs404() throws Exception {
        mvc.perform(get("/api/conversations/999999")).andExpect(status().isNotFound());
    }
}
