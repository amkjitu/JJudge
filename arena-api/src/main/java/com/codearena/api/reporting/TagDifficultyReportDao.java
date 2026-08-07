package com.codearena.api.reporting;

import com.codearena.api.web.dto.TagDifficultyRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Reporting query written with Spring JDBC rather than JPA - a deliberate contrast to the rest
 * of the persistence layer.
 *
 * <p>The reason is not that JPA <em>could not</em> express this, but that it should not have to.
 * The query is four CTEs of set-level aggregation returning a shape that corresponds to no
 * entity: forcing it through JPQL would mean either a native query hidden behind a repository
 * interface or a constructor expression over an unmappable projection. Reporting reads over the
 * whole table are the case where dropping to SQL is the simpler, faster and more honest choice,
 * and Spring JDBC gives that without giving up connection management or exception translation.
 *
 * <p>Postgres specifics used here that JPQL has no vocabulary for: {@code FILTER (WHERE ...)}
 * on aggregates, {@code bool_or}, and {@code NULLS LAST} ordering.
 */
@Repository
public class TagDifficultyReportDao {

    private static final String SQL = """
            WITH problem_counts AS (SELECT tag_id, COUNT(*) AS problem_count
                                    FROM problem_tags
                                    GROUP BY tag_id),
                 -- One row per (tag, user, problem): how many times that user attacked that
                 -- problem, and whether they ever landed it.
                 attempt AS (SELECT pt.tag_id,
                                    s.user_id,
                                    s.problem_id,
                                    COUNT(*)                  AS attempts,
                                    bool_or(s.verdict = 'AC') AS solved
                             FROM submissions s
                                      JOIN problem_tags pt ON pt.problem_id = s.problem_id
                             GROUP BY pt.tag_id, s.user_id, s.problem_id),
                 attempt_stats AS (SELECT tag_id,
                                          COUNT(DISTINCT user_id) FILTER (WHERE solved) AS distinct_solvers,
                                          AVG(attempts) FILTER (WHERE solved)           AS avg_attempts_to_solve
                                   FROM attempt
                                   GROUP BY tag_id),
                 submission_stats AS (SELECT pt.tag_id,
                                             COUNT(*)                                          AS total_submissions,
                                             COUNT(*) FILTER (WHERE s.verdict = 'AC')          AS accepted_submissions,
                                             AVG(s.runtime_ms) FILTER (WHERE s.verdict = 'AC') AS avg_accepted_runtime_ms
                                      FROM submissions s
                                               JOIN problem_tags pt ON pt.problem_id = s.problem_id
                                      GROUP BY pt.tag_id)
            SELECT t.name                              AS tag,
                   COALESCE(pc.problem_count, 0)       AS problem_count,
                   COALESCE(ss.total_submissions, 0)   AS total_submissions,
                   COALESCE(ss.accepted_submissions, 0) AS accepted_submissions,
                   COALESCE(ast.distinct_solvers, 0)   AS distinct_solvers,
                   ast.avg_attempts_to_solve           AS avg_attempts_to_solve,
                   ss.avg_accepted_runtime_ms          AS avg_accepted_runtime_ms
            FROM tags t
                     LEFT JOIN problem_counts pc ON pc.tag_id = t.id
                     LEFT JOIN attempt_stats ast ON ast.tag_id = t.id
                     LEFT JOIN submission_stats ss ON ss.tag_id = t.id
            WHERE (:minProblems = 0 OR COALESCE(pc.problem_count, 0) >= :minProblems)
            ORDER BY %s
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TagDifficultyReportDao(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TagDifficultyRow> report(TagDifficultySort sort, int minProblems) {
        // Safe interpolation: the fragment comes from the enum, never from the request.
        String sql = SQL.formatted(sort.orderByClause());
        MapSqlParameterSource params = new MapSqlParameterSource("minProblems", minProblems);
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    private static final RowMapper<TagDifficultyRow> ROW_MAPPER = (rs, rowNum) -> {
        long total = rs.getLong("total_submissions");
        long accepted = rs.getLong("accepted_submissions");

        // Left as null rather than 0.0 for untouched tags: "nobody has tried this" and
        // "everybody who tried failed" are different facts and should not render alike.
        Double acceptanceRate = total == 0 ? null : (double) accepted / total;

        return new TagDifficultyRow(
                rs.getString("tag"),
                rs.getInt("problem_count"),
                total,
                accepted,
                rs.getLong("distinct_solvers"),
                acceptanceRate,
                nullableDouble(rs, "avg_attempts_to_solve"),
                nullableDouble(rs, "avg_accepted_runtime_ms")
        );
    };

    /**
     * {@code ResultSet#getDouble} returns 0.0 for SQL NULL, which would silently turn "no data"
     * into "zero". {@code wasNull()} is the only way to tell them apart.
     */
    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
