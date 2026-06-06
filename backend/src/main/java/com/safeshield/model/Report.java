package com.safeshield.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reports")
public class Report {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private Session session;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary = "";

    private Double riskScore = 0.0;

    @Column(columnDefinition = "TEXT")
    private String violenceTypes = "[]";

    @Column(columnDefinition = "TEXT")
    private String matchedLaws = "[]";

    @Column(columnDefinition = "TEXT")
    private String lawRelevanceScores = "[]";

    @Column(columnDefinition = "TEXT")
    private String expectedMeasureRange = "[0,5]";

    @Column(columnDefinition = "TEXT")
    private String evidenceGuide = "[]";

    private String assessmentStatus = "";

    @Column(columnDefinition = "TEXT")
    private String assessmentDetails = "[]";

    @Column(columnDefinition = "TEXT")
    private String recommendedActions = "[]";

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Session getSession() { return session; }
    public void setSession(Session session) { this.session = session; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Double getRiskScore() { return riskScore; }
    public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }
    public String getViolenceTypes() { return violenceTypes; }
    public void setViolenceTypes(String violenceTypes) { this.violenceTypes = violenceTypes; }
    public String getMatchedLaws() { return matchedLaws; }
    public void setMatchedLaws(String matchedLaws) { this.matchedLaws = matchedLaws; }
    public String getLawRelevanceScores() { return lawRelevanceScores; }
    public void setLawRelevanceScores(String lawRelevanceScores) { this.lawRelevanceScores = lawRelevanceScores; }
    public String getExpectedMeasureRange() { return expectedMeasureRange; }
    public void setExpectedMeasureRange(String expectedMeasureRange) { this.expectedMeasureRange = expectedMeasureRange; }
    public String getEvidenceGuide() { return evidenceGuide; }
    public void setEvidenceGuide(String evidenceGuide) { this.evidenceGuide = evidenceGuide; }
    public String getAssessmentStatus() { return assessmentStatus; }
    public void setAssessmentStatus(String assessmentStatus) { this.assessmentStatus = assessmentStatus; }
    public String getAssessmentDetails() { return assessmentDetails; }
    public void setAssessmentDetails(String assessmentDetails) { this.assessmentDetails = assessmentDetails; }
    public String getRecommendedActions() { return recommendedActions; }
    public void setRecommendedActions(String recommendedActions) { this.recommendedActions = recommendedActions; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
