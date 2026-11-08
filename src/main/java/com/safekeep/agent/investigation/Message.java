package com.safekeep.agent.investigation;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class Message {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long investigationId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private MessageAuthor author;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private MessageType type;
    @Column(length = 4000, nullable = false) private String body;
    private String username;
    @Column(nullable = false) private Instant createdAt;

    protected Message() { }

    public Message(Long investigationId, MessageAuthor author, MessageType type, String body, String username, Instant createdAt) {
        this.investigationId = investigationId;
        this.author = author;
        this.type = type;
        this.body = body;
        this.username = username;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getInvestigationId() { return investigationId; }
    public MessageAuthor getAuthor() { return author; }
    public MessageType getType() { return type; }
    public String getBody() { return body; }
    public String getUsername() { return username; }
    public Instant getCreatedAt() { return createdAt; }
}
