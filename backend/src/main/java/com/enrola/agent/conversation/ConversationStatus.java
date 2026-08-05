package com.enrola.agent.conversation;

public enum ConversationStatus {
    ACTIVE,
    GOAL_MET,          // booked; exactly one further closing message is allowed
    GOAL_MET_CLOSED,
    UNSUBSCRIBED,
    ENDED_ABUSE,
    ENDED_GIVE_UP;

    public boolean isTerminal() {
        return this != ACTIVE && this != GOAL_MET;
    }
}
