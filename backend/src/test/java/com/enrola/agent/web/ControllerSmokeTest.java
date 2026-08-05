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

        // Lead 2: lead 1 has the seeded conversation from data.sql, and POST /api/conversations
        // now resumes rather than creates. This test is about the create-and-run path.
        var body = mvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leadId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.messages[0].direction").value("OUTBOUND"))
                .andExpect(jsonPath("$.messages[0].structuredOutput.stage").value("SITUATION"))
                .andReturn().getResponse().getContentAsString();
        var id = com.jayway.jsonpath.JsonPath.read(body, "$.id").toString();

        // Compare `characters` to the actual body length rather than merely asserting it is a
        // number. The point of computing it server-side is that the simulator shows the same
        // count the guardrail enforced - and isNumber() cannot tell a correct count from a
        // hardcoded one.
        int characters = com.jayway.jsonpath.JsonPath.read(body, "$.messages[0].characters");
        String sent = com.jayway.jsonpath.JsonPath.read(body, "$.messages[0].body");
        org.assertj.core.api.Assertions.assertThat(characters).isEqualTo(sent.length());

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

    /** The reviewer's first click, without an API key: a finished thread and no model call. */
    @Test
    void clickingTheFirstLeadReturnsTheSeededConversation() throws Exception {
        llm.reset(); // nothing queued: the stub throws if this path calls the model at all

        mvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leadId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("GOAL_MET"))
                .andExpect(jsonPath("$.messages.length()").value(9))
                .andExpect(jsonPath("$.messages[8].structuredOutput.endReason").value("BOOKED"))
                .andExpect(jsonPath("$.bookings.length()").value(1));

        org.assertj.core.api.Assertions.assertThat(llm.callCount()).isZero();
    }

    @Test
    void unknownConversationIs404() throws Exception {
        mvc.perform(get("/api/conversations/999999")).andExpect(status().isNotFound());
    }

    @Test
    void aClosedConversationRejectsFurtherMessagesWith409() throws Exception {
        llm.reset();
        llm.queue(LlmResponse.message(TURN));

        var body = mvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leadId\":3}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var id = com.jayway.jsonpath.JsonPath.read(body, "$.id").toString();

        // "stop" takes the fast opt-out path: terminal status, zero model calls, so nothing
        // needs queueing for this turn.
        mvc.perform(post("/api/conversations/" + id + "/messages")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"stop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNSUBSCRIBED"))
                .andExpect(jsonPath("$.terminal").value(true));

        mvc.perform(post("/api/conversations/" + id + "/messages")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hello?\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conversation_closed"));
    }
}
