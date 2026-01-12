package com.rafael.aireceptionistapi.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RestaurantKnowledgeRepository
        extends JpaRepository<RestaurantKnowledge, Long> {

    Optional<RestaurantKnowledge> findByPhoneNumber(String phoneNumber);
}
