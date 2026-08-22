package io.macrosquare.policy.adapter.out.persistence;

import io.macrosquare.policy.application.port.out.PolicyAnalysisRepository;
import io.macrosquare.policy.application.port.out.PolicyPersistenceException;
import io.macrosquare.policy.domain.model.PolicyDirection;
import io.macrosquare.policy.domain.model.PolicyDocument;
import io.macrosquare.policy.domain.model.PolicyDocumentAnalysis;
import io.macrosquare.policy.domain.model.PolicyDocumentType;
import io.macrosquare.policy.domain.model.PolicyEvidence;
import io.macrosquare.policy.domain.model.PolicyTone;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class JdbcPolicyAnalysisRepository implements PolicyAnalysisRepository {

    private static final String UPSERT = """
            insert into policy.document_analysis (
                document_id, source, title, document_type, published_at, source_url,
                tone, tone_score, confidence, dovish_weight, hawkish_weight, summary, analyzed_at
            ) values (
                :documentId, :source, :title, :documentType, :publishedAt, :sourceUrl,
                :tone, :toneScore, :confidence, :dovishWeight, :hawkishWeight, :summary, clock_timestamp()
            )
            on conflict (document_id) do update set
                source = excluded.source,
                title = excluded.title,
                document_type = excluded.document_type,
                published_at = excluded.published_at,
                source_url = excluded.source_url,
                tone = excluded.tone,
                tone_score = excluded.tone_score,
                confidence = excluded.confidence,
                dovish_weight = excluded.dovish_weight,
                hawkish_weight = excluded.hawkish_weight,
                summary = excluded.summary,
                analyzed_at = excluded.analyzed_at
            """;
    private static final String DELETE_EVIDENCE =
            "delete from policy.document_evidence where document_id = :documentId";
    private static final String INSERT_EVIDENCE = """
            insert into policy.document_evidence (
                document_id, ordinal, phrase, direction, weight, excerpt
            ) values (
                :documentId, :ordinal, :phrase, :direction, :weight, :excerpt
            )
            """;
    private static final String LATEST = """
            with latest as (
                select * from policy.document_analysis
                order by published_at desc, document_id
                limit :limit
            )
            select d.document_id, d.source, d.title, d.document_type, d.published_at, d.source_url,
                   d.tone, d.tone_score, d.confidence, d.dovish_weight, d.hawkish_weight, d.summary,
                   e.ordinal, e.phrase, e.direction, e.weight, e.excerpt
            from latest d
            left join policy.document_evidence e on e.document_id = d.document_id
            order by d.published_at desc, d.document_id, e.ordinal
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcPolicyAnalysisRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public int save(List<PolicyDocumentAnalysis> analyses) {
        if (analyses == null || analyses.isEmpty()) return 0;
        try {
            return transactions.execute(status -> {
                for (var analysis : analyses) saveOne(analysis);
                return analyses.size();
            });
        } catch (RuntimeException error) {
            throw new PolicyPersistenceException("Unable to persist policy analyses", error);
        }
    }

    @Override
    public List<PolicyDocumentAnalysis> loadLatest(int limit) {
        if (limit < 1 || limit > 30) throw new IllegalArgumentException("limit must be between 1 and 30");
        try {
            var builders = new LinkedHashMap<String, AnalysisBuilder>();
            jdbc.query(LATEST, new MapSqlParameterSource("limit", limit), row -> {
                var id = row.getString("document_id");
                var source = row.getString("source");
                var title = row.getString("title");
                var documentType = PolicyDocumentType.valueOf(row.getString("document_type"));
                var publishedAt = row.getTimestamp("published_at").toInstant();
                var sourceUrl = row.getString("source_url");
                var tone = PolicyTone.valueOf(row.getString("tone"));
                var toneScore = row.getInt("tone_score");
                var confidence = row.getInt("confidence");
                var dovishWeight = row.getInt("dovish_weight");
                var hawkishWeight = row.getInt("hawkish_weight");
                var summary = row.getString("summary");
                var builder = builders.computeIfAbsent(id, ignored -> new AnalysisBuilder(
                        new PolicyDocument(
                                id,
                                source,
                                title,
                                documentType,
                                publishedAt,
                                sourceUrl,
                                ""
                        ),
                        tone,
                        toneScore,
                        confidence,
                        dovishWeight,
                        hawkishWeight,
                        summary
                ));
                var phrase = row.getString("phrase");
                if (phrase != null) builder.evidence.add(new PolicyEvidence(
                        phrase,
                        PolicyDirection.valueOf(row.getString("direction")),
                        row.getInt("weight"),
                        row.getString("excerpt")
                ));
            });
            return builders.values().stream().map(AnalysisBuilder::build).toList();
        } catch (RuntimeException error) {
            throw new PolicyPersistenceException("Unable to load policy analyses", error);
        }
    }

    private void saveOne(PolicyDocumentAnalysis value) {
        var document = value.document();
        jdbc.update(UPSERT, new MapSqlParameterSource()
                .addValue("documentId", document.id())
                .addValue("source", document.source())
                .addValue("title", document.title())
                .addValue("documentType", document.type().name())
                .addValue("publishedAt", Timestamp.from(document.publishedAt()))
                .addValue("sourceUrl", document.url())
                .addValue("tone", value.tone().name())
                .addValue("toneScore", value.toneScore())
                .addValue("confidence", value.confidence())
                .addValue("dovishWeight", value.dovishWeight())
                .addValue("hawkishWeight", value.hawkishWeight())
                .addValue("summary", value.summary()));
        jdbc.update(DELETE_EVIDENCE, new MapSqlParameterSource("documentId", document.id()));
        if (!value.evidence().isEmpty()) {
            var params = new MapSqlParameterSource[value.evidence().size()];
            for (var index = 0; index < value.evidence().size(); index++) {
                var evidence = value.evidence().get(index);
                params[index] = new MapSqlParameterSource()
                        .addValue("documentId", document.id())
                        .addValue("ordinal", index)
                        .addValue("phrase", evidence.phrase())
                        .addValue("direction", evidence.direction().name())
                        .addValue("weight", evidence.weight())
                        .addValue("excerpt", evidence.excerpt());
            }
            jdbc.batchUpdate(INSERT_EVIDENCE, params);
        }
    }

    private static final class AnalysisBuilder {
        private final PolicyDocument document;
        private final PolicyTone tone;
        private final int toneScore;
        private final int confidence;
        private final int dovishWeight;
        private final int hawkishWeight;
        private final String summary;
        private final List<PolicyEvidence> evidence = new ArrayList<>();

        private AnalysisBuilder(
                PolicyDocument document,
                PolicyTone tone,
                int toneScore,
                int confidence,
                int dovishWeight,
                int hawkishWeight,
                String summary
        ) {
            this.document = document;
            this.tone = tone;
            this.toneScore = toneScore;
            this.confidence = confidence;
            this.dovishWeight = dovishWeight;
            this.hawkishWeight = hawkishWeight;
            this.summary = summary;
        }

        private PolicyDocumentAnalysis build() {
            return new PolicyDocumentAnalysis(
                    document, tone, toneScore, confidence, dovishWeight, hawkishWeight, evidence, summary);
        }
    }
}
