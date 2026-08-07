package com.codearena.api.service;

import com.codearena.api.domain.Tag;
import com.codearena.api.repository.TagRepository;
import com.codearena.api.web.dto.TagResponse;
import com.codearena.api.web.mapper.TagMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository tagRepository;
    private final TagMapper tagMapper;

    public TagService(TagRepository tagRepository, TagMapper tagMapper) {
        this.tagRepository = tagRepository;
        this.tagMapper = tagMapper;
    }

    /**
     * Every tag with its prerequisite edges, loaded in one query and mapped inside the
     * transaction. This is the whole DAG - 30 nodes that change about never, so paginating it
     * would be ceremony.
     */
    public List<TagResponse> findAllWithPrerequisites() {
        return tagRepository.findAllWithPrerequisites().stream()
                .sorted(Comparator.comparing(Tag::getName))
                .map(tagMapper::toResponse)
                .toList();
    }
}
