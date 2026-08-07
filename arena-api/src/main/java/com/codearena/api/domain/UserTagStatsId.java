package com.codearena.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Composite key for {@link UserTagStats}. Value object, so full value-based equality here is
 * correct - unlike the entities, which compare on surrogate id only.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserTagStatsId implements Serializable {

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "tag_id")
    private Long tagId;
}
