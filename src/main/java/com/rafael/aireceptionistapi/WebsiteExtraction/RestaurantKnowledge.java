package com.rafael.aireceptionistapi.WebsiteExtraction;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "restaurant_knowledge")
public class RestaurantKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Twilio-Nummer, z. B. "+431234567"
    @Column(nullable = false)
    private String phoneNumber;

    @Column(nullable = false)
    private String sourceUrl;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String rawText;

    private OffsetDateTime lastUpdated;

    // getters / setters
    public Long getId() { return id; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public OffsetDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(OffsetDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
}
