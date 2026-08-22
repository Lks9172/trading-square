package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyGuidanceMetric;
import io.macrosquare.company.domain.model.CompanyGuidanceMetric.Direction;
import io.macrosquare.company.domain.model.CompanyGuidanceMetricValue;
import io.macrosquare.company.domain.model.CompanyGuidanceSummary;
import io.macrosquare.company.domain.model.CompanyGuidanceSummary.Stance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure, bounded parser for revenue, margin, CAPEX, and free-cash-flow guidance.
 *
 * <p>The policy prefers prospective outlook clauses and rejects historical-result,
 * reconciliation, definition, and safe-harbor text. It deliberately recognizes
 * directional verbs on either side of a metric, unlike the legacy one-way regex.</p>
 */
public final class CompanyGuidanceParsingPolicy {

    private static final int MAX_CLAUSE_CHARACTERS = 320;
    private static final int MAX_EVIDENCE = 5;

    private static final Pattern FORWARD_LANGUAGE = Pattern.compile(
            "\\b(expect(?:ed|s|ing)?|guidance|outlook|forecast|project(?:ed|s|ing)?|"
                    + "anticipat(?:e|ed|es|ing)|target(?:ed|s|ing)?|estimate(?:d|s)?|"
                    + "will be|plans? to|sees)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GUIDANCE_LANGUAGE = Pattern.compile(
            "\\b(guidance|outlook|forecast|target)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern STRONG_GUIDANCE_LANGUAGE = Pattern.compile(
            "\\b(guidance|outlook|forecast)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXPECTATION_ACTION = Pattern.compile(
            "\\b(expect(?:ed|s|ing)?|project(?:ed|s|ing)?|anticipat(?:e|ed|es|ing)|"
                    + "estimate(?:d|s)?|will be|plans? to|sees)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FUTURE_PERIOD = Pattern.compile(
            "\\b(next|upcoming|full[- ]year|fiscal|quarter|year|fy\\s*\\d{2,4}|q[1-4])\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NUMERIC_EVIDENCE = Pattern.compile(
            "[$€%]|\\b\\d+(?:\\.\\d+)?\\s*(?:billion|bn|million|mn|bps|basis points?|[bm])\\b|"
                    + "\\b(?:low|mid|high)[- ]?(?:single|double|teens|[2-6]0s)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CURRENT_GUIDANCE_TABLE_HEADER = Pattern.compile(
            "\\bcurrent guidance\\s+prior guidance\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COMPARATIVE_GUIDANCE_TABLE_HEADER = Pattern.compile(
            "\\b(?:current guidance\\s+prior guidance|quarterly guidance\\s*(?:&|and)\\s*results|"
                    + "original\\s+updated\\s+low\\s+high\\s+low\\s+high)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEADING_PERCENT_BAND = Pattern.compile(
            "\\b(?:low|mid|high)[- ]?(?:single|double)[- ]?digits?\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GUIDANCE_TABLE_UNIT_CONTEXT = Pattern.compile(
            "\\((?:\\$\\s+)?in (?:millions?|billions?)(?:,[^)]*)?\\)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SUBSEQUENT_GUIDANCE_METRIC = Pattern.compile(
            "\\b(?:adjusted\\s+)?(?:ebitda|ebit|operating expenses?|earnings per share|eps|free cash flow|fcf|"
                    + "capex|capital expenditures?|gross margins?|operating margins?)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXCLUDED_CONTEXT = Pattern.compile(
            "forward-looking statements?|safe harbor|not historical|reconciliation|"
                    + "non-gaap financial measures?|is calculated as|definition of|for comparison purposes|"
                    + "expectations with respect to|outlook including expected (?:sales|financial results)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HISTORICAL_CONTEXT = Pattern.compile(
            "\\b(reported|record|for the (?:quarter|year) ended|actual results?|historical)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COMPONENT_IMPACT_ON_MARGIN = Pattern.compile(
            "\\bimpact\\s+on\\b.{0,80}\\b(?:gross|operating)?\\s*margins?\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DEFERRED_REVENUE_CONTEXT = Pattern.compile(
            "\\bdeferred revenue\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ACCOUNTING_ESTIMATE_CONTEXT = Pattern.compile(
            "\\b(?:recogniz(?:e|ed|es|ing)|recognition of)\\s+estimated contract revenue\\b|"
                    + "\\bestimated contract revenue and resulting income\\b|"
                    + "\\bpercentage of (?:total project )?completion\\b|"
                    + "\\badjustments? to .{0,20}revenue .{0,80}reflect changes in estimates\\b|"
                    + "\\bactual amounts subsequently reported by .{0,40}licensees\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HISTORICAL_MOVEMENT_CONTEXT = Pattern.compile(
            "\\b(?:revenue|net sales|total sales)\\b.{0,60}"
                    + "\\b(?:increased|decreased|grew|declined|was flat|were flat|totaled|reached)\\b|"
                    + "\\b(?:increased|decreased|grew|declined|was flat|were flat|totaled|reached)\\b.{0,60}"
                    + "\\b(?:revenue|net sales|total sales)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HISTORICAL_TABLE_CONTEXT = Pattern.compile(
            "statements? of comprehensive income|three months ended|fiscal quarter ending|"
                    + "\\bttm\\b|\\b(?:q[1-4]|fy\\d{2,4})\\s+summary\\b|"
                    + "\\bover\\s+(?:1q|2q|3q|4q)\\d{2}\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GUIDANCE_COMPARISON_TABLE_CONTEXT = Pattern.compile(
            "\\bguidance\\s+(?:net\\s+)?revenue\\s*\\(us\\$\\s+billions?\\)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FCF_HISTORICAL_TABLE_CONTEXT = Pattern.compile(
            "\\bfree cash flow\\s*\\(non-gaap\\)\\s*[\\d,]+\\s+[\\d,]+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FCF_PROJECT_DESCRIPTOR_CONTEXT = Pattern.compile(
            "\\bfree cash flow\\s+(?:generative|generating)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HISTORICAL_FCF_CONTEXT = Pattern.compile(
            "\\b(?:resulting in\\s+)?free cash flow of\\b|"
                    + "\\bfree cash flow\\b.{0,100}\\b(?:during|totaled|increased|decreased)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONTRACT_REVENUE_SCHEDULE_CONTEXT = Pattern.compile(
            "\\bprojected revenues? from tenant contracts?\\b|\\bactive licenses?\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CUSTOMER_REVENUE_STATISTIC_CONTEXT = Pattern.compile(
            "\\bmonthly .{0,50}revenue per .{0,40}customer\\b|\\bcustomer relationship penetration\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BALANCE_SHEET_CONTEXT = Pattern.compile(
            "\\b(?:unaudited\\s+)?assets\\b.{0,50}\\bcurrent assets\\b|\\bbalance sheets?\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CAPEX_HISTORICAL_TABLE_CONTEXT = Pattern.compile(
            "\\bdiscretionary capital expenditures?\\b|\\bsustaining capital expenditures?\\b|"
                    + "\\bsummary of capital expenditures?\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CAPEX_HISTORICAL_MOVEMENT_CONTEXT = Pattern.compile(
            "\\bkept capital expenditures?\\b.{0,80}\\b(?:below|above)\\b|"
                    + "\\bcapital expenditures?\\b.{0,60}\\b(?:totaled|were|was)\\b|"
                    + "\\bcapital expenditures? (?:increased|decreased) mainly due\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REVENUE_NON_GUIDANCE_CONTEXT = Pattern.compile(
            "\\binternal revenue service\\b|"
                    + "\\bleases? are expected to generate annual rental revenue\\b|"
                    + "\\bexpected to generate annual rental revenue\\b|"
                    + "\\bexpected annual rental revenue\\b|"
                    + "\\brevenue growth alongside \\$[\\d,.]+(?:\\s*(?:billion|bn|million|mn|[bm]))?\\s+non-gaap\\b|"
                    + "\\bseek to increase .{0,60}revenues? by adding .{0,60}tenants\\b|"
                    + "\\bestimate that .{0,100}impacted revenue growth\\b|"
                    + "\\b(?:operating income|ebitda|margin) guidance.{0,100}percent of projected revenue\\b|"
                    + "\\bestimates regarding revenue\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FORECAST_COMPARISON_CONTEXT = Pattern.compile(
            "\\b(?:above|below|beat|beating|exceeded|missed)\\b.{0,50}"
                    + "\\b(?:guidance|outlook|forecast|expectations?)\\b|"
                    + "\\b(?:guidance|outlook|forecast|expectations?)\\b.{0,50}"
                    + "\\b(?:above|below|beat|beating|exceeded|missed)\\b|"
                    + "\\bcame in\\b.{0,80}\\b(?:guidance|outlook|forecast|expectations?)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RAISED_ACTION = Pattern.compile(
            "\\b(rais(?:e|ed|es|ing)|lift(?:ed|s|ing)|boost(?:ed|s|ing)|revised\\s+upward|"
                    + "increas(?:e|ed|es|ing)(?=.{0,120}\\bguidance\\b)|"
                    + "(?:an?\\s+)?increase\\s+from\\b.{0,100}\\b(?:prior|previous(?:ly)?)|"
                    + "increased\\s+from\\s+(?:our\\s+|the\\s+)?prior(?:\\s+guidance)?(?:\\s+range)?|"
                    + "(?:roughly\\s+)?flat\\s*\\(\\s*versus\\s+down\\s+previously\\s*\\))(?=\\W|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LOWERED_ACTION = Pattern.compile(
            "\\b(lower(?:ed|s|ing)|cuts?|cutting|reduc(?:e|ed|es|ing)|revised\\s+downward|"
                    + "decreas(?:e|ed|es|ing)(?=.{0,120}\\bguidance\\b)|"
                    + "(?:a\\s+)?decrease\\s+from\\b.{0,100}\\b(?:prior|previous(?:ly)?)|"
                    + "decreased\\s+from\\s+(?:our\\s+|the\\s+)?prior(?:\\s+guidance)?(?:\\s+range)?|"
                    + "down\\s*\\(\\s*versus\\s+up\\s+previously\\s*\\))(?=\\W|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern AFFIRMED_ACTION = Pattern.compile(
            "\\b((?:re)?affirm(?:ed|s|ing)|maintain(?:ed|s|ing)|reiterat(?:e|ed|es|ing)|unchanged|"
                    + "consistent\\s+with\\s+(?:our\\s+|the\\s+)?(?:company(?:'s)?\\s+)?"
                    + "(?:full[- ]year\\s+)?guidance)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final CompanyGuidanceMetricValueParser valueParser;

    public CompanyGuidanceParsingPolicy() {
        this(new CompanyGuidanceMetricValueParser());
    }

    CompanyGuidanceParsingPolicy(CompanyGuidanceMetricValueParser valueParser) {
        this.valueParser = java.util.Objects.requireNonNull(valueParser);
    }

    public CompanyGuidanceSummary summarize(String text) {
        if (text == null || text.isBlank()) {
            return new CompanyGuidanceSummary(Stance.UNCLEAR, null, null, null, null, List.of());
        }
        var normalized = normalize(text);
        var clauses = splitClauses(normalized);
        var revenue = parseMetric(clauses, MetricKind.REVENUE);
        var margin = parseMetric(clauses, MetricKind.MARGIN);
        var capex = parseMetric(clauses, MetricKind.CAPEX);
        var freeCashFlow = parseMetric(clauses, MetricKind.FREE_CASH_FLOW);

        var directions = EnumSet.noneOf(Direction.class);
        addExplicitDirection(directions, revenue);
        addExplicitDirection(directions, margin);
        addExplicitDirection(directions, capex);
        addExplicitDirection(directions, freeCashFlow);
        directions.addAll(genericGuidanceDirections(clauses));

        var evidence = new LinkedHashSet<String>();
        firstDirectionalGuidanceClause(clauses).ifPresent(evidence::add);
        addEvidence(evidence, revenue);
        addEvidence(evidence, margin);
        addEvidence(evidence, capex);
        addEvidence(evidence, freeCashFlow);

        return new CompanyGuidanceSummary(
                stance(directions),
                revenue,
                margin,
                capex,
                freeCashFlow,
                evidence.stream().limit(MAX_EVIDENCE).toList()
        );
    }

    /** Generic compatibility parser used by golden/characterization tests. */
    public CompanyGuidanceMetricValue parseValue(String raw) {
        return valueParser.parseGeneric(raw);
    }

    private CompanyGuidanceMetric parseMetric(List<String> clauses, MetricKind metric) {
        var candidates = new ArrayList<Candidate>();
        for (var index = 0; index < clauses.size(); index++) {
            var clause = clauses.get(index);
            var matcher = metric.pattern.matcher(clause);
            while (matcher.find()) {
                var slice = isolateMetricClause(clause, matcher.start(), matcher.end(), metric);
                var score = score(slice, metric);
                if (score >= 7 && isProspective(slice, metric)) {
                    candidates.add(new Candidate(
                            score,
                            index,
                            matcher.start(),
                            slice.text(),
                            parseValue(metric, valueWindow(slice)),
                            direction(slice)
                    ));
                }
            }
        }
        if (candidates.isEmpty()) return null;
        var candidate = candidates.stream()
                .max(Comparator.comparing(Candidate::structured)
                        .thenComparingInt(Candidate::score)
                        .thenComparing(Comparator.comparingInt(Candidate::clauseIndex).reversed())
                        .thenComparing(Comparator.comparingInt(Candidate::metricIndex).reversed()))
                .orElseThrow();
        if (!candidate.structured() && candidate.direction() == Direction.MENTIONED) return null;
        var clause = candidate.text();
        return new CompanyGuidanceMetric(candidate.direction(), clause, candidate.value());
    }

    private CompanyGuidanceMetricValue parseValue(MetricKind metric, String raw) {
        return switch (metric) {
            case MARGIN -> valueParser.parseMargin(raw);
            case FREE_CASH_FLOW -> valueParser.parseGrowthFirst(raw);
            default -> valueParser.parseMonetaryFirst(raw);
        };
    }

    private static List<String> splitClauses(String text) {
        var prepared = text
                .replace('\u2022', '\n')
                .replace('\u25cf', '\n')
                .replace('\u25aa', '\n');
        var parts = prepared.split("[\\n;]+|\\.(?!\\d)\\s+(?=[A-Z])|(?<=[!?])\\s+");
        var result = new ArrayList<String>(parts.length);
        for (var part : parts) {
            var normalized = normalizeClause(part);
            if (!normalized.isBlank()) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static MetricSlice isolateMetricClause(
            String clause,
            int metricStart,
            int metricEnd,
            MetricKind metric
    ) {
        var start = 0;
        var comma = clause.lastIndexOf(',', metricStart);
        var conjunction = clause.toLowerCase(Locale.ROOT).lastIndexOf(" and ", metricStart);
        var delimiter = Math.max(comma, conjunction);
        if (delimiter >= 0 && metricStart - delimiter <= 120) {
            start = delimiter + (delimiter == conjunction ? 5 : 1);
        } else if (metricStart > 140) {
            start = metricStart - 140;
        }

        if (metric == MetricKind.REVENUE) {
            var tableHeader = lastMatchBefore(COMPARATIVE_GUIDANCE_TABLE_HEADER, clause, metricStart);
            if (tableHeader >= 0 && metricStart - tableHeader <= 260) {
                var unitContext = lastMatchBefore(GUIDANCE_TABLE_UNIT_CONTEXT, clause, tableHeader);
                start = unitContext >= 0 && tableHeader - unitContext <= 260
                        ? Math.min(start, unitContext)
                        : Math.min(start, tableHeader);
            }
        }
        var unitContext = lastMatchBefore(GUIDANCE_TABLE_UNIT_CONTEXT, clause, metricStart);
        if (unitContext >= 0 && metricStart - unitContext <= 220) {
            start = Math.min(start, unitContext);
        }

        var end = Math.min(clause.length(), metricEnd + 280);
        for (var other : MetricKind.values()) {
            if (other == metric) continue;
            var matcher = other.pattern.matcher(clause);
            while (matcher.find()) {
                if (matcher.start() > metricEnd) {
                    end = Math.min(end, matcher.start());
                    break;
                }
            }
        }
        if (end <= start) end = Math.min(clause.length(), metricEnd + 200);
        var text = bounded(clause.substring(start, end));
        var relativeStart = Math.max(0, metricStart - start);
        if (relativeStart >= text.length()) relativeStart = Math.max(0, text.length() - 1);
        var relativeEnd = Math.min(text.length(), Math.max(relativeStart + 1, metricEnd - start));
        return new MetricSlice(text, relativeStart, relativeEnd);
    }

    private static String valueWindow(MetricSlice slice) {
        var end = Math.min(slice.text().length(), slice.metricEnd() + 180);
        var trailing = slice.text().substring(slice.metricEnd(), end);
        var header = COMPARATIVE_GUIDANCE_TABLE_HEADER.matcher(slice.text());
        if (header.find() && header.start() < slice.metricStart()) {
            var nextMetric = SUBSEQUENT_GUIDANCE_METRIC.matcher(slice.text());
            while (nextMetric.find()) {
                if (nextMetric.start() > slice.metricEnd()) {
                    end = Math.min(end, nextMetric.start());
                    break;
                }
            }
            return slice.text().substring(0, end);
        }
        var nextMetric = SUBSEQUENT_GUIDANCE_METRIC.matcher(slice.text());
        if (nextMetric.find(slice.metricEnd())) {
            end = Math.min(end, nextMetric.start());
            trailing = slice.text().substring(slice.metricEnd(), end);
        }
        var leadingStart = Math.max(0, slice.metricStart() - 80);
        var leading = slice.text().substring(leadingStart, slice.metricStart());
        var leadingBand = LEADING_PERCENT_BAND.matcher(leading);
        if (leadingBand.find()) {
            return slice.text().substring(
                    leadingStart + leadingBand.start(),
                    Math.min(slice.text().length(), slice.metricEnd() + 60)
            );
        }
        var unitContext = GUIDANCE_TABLE_UNIT_CONTEXT.matcher(slice.text());
        if (unitContext.find() && unitContext.start() < slice.metricStart()) {
            return slice.text().substring(unitContext.start(), end);
        }
        // Tables frequently place an unrelated EPS range immediately before CAPEX.
        // Prefer values following the matched metric and only include the leading
        // context when no trailing numeric evidence exists (for "$3-4bn capex").
        if (NUMERIC_EVIDENCE.matcher(trailing).find()) return trailing;
        var start = Math.max(0, slice.metricStart() - 32);
        return slice.text().substring(start, end);
    }

    private static String actionWindow(MetricSlice slice) {
        var start = Math.max(0, slice.metricStart() - 120);
        var end = Math.min(slice.text().length(), slice.metricEnd() + 120);
        return slice.text().substring(start, end);
    }

    private static int score(MetricSlice slice, MetricKind metric) {
        var clause = slice.text();
        var score = 0;
        if (withinDistance(slice, EXPECTATION_ACTION, 60)) score += 12;
        if (withinDistance(slice, GUIDANCE_LANGUAGE, 100)) score += 5;
        if (NUMERIC_EVIDENCE.matcher(valueWindow(slice)).find()) score += 4;
        if (FUTURE_PERIOD.matcher(clause).find()) score += 2;
        if (withinDistance(slice, RAISED_ACTION, 100)
                || withinDistance(slice, LOWERED_ACTION, 100)
                || withinDistance(slice, AFFIRMED_ACTION, 100)) {
            score += 5;
        }
        var actionWindow = actionWindow(slice);
        if (EXCLUDED_CONTEXT.matcher(actionWindow).find()) score -= 14;
        if (HISTORICAL_CONTEXT.matcher(actionWindow).find()
                && !withinDistance(slice, EXPECTATION_ACTION, 60)) score -= 12;
        if (metric == MetricKind.MARGIN && COMPONENT_IMPACT_ON_MARGIN.matcher(clause).find()) score -= 12;
        if (metric == MetricKind.REVENUE && DEFERRED_REVENUE_CONTEXT.matcher(actionWindow).find()) score -= 16;
        if (metric == MetricKind.REVENUE && ACCOUNTING_ESTIMATE_CONTEXT.matcher(actionWindow).find()) score -= 20;
        if (metric == MetricKind.REVENUE && (CONTRACT_REVENUE_SCHEDULE_CONTEXT.matcher(actionWindow).find()
                || CUSTOMER_REVENUE_STATISTIC_CONTEXT.matcher(actionWindow).find()
                || BALANCE_SHEET_CONTEXT.matcher(actionWindow).find()
                || REVENUE_NON_GUIDANCE_CONTEXT.matcher(actionWindow).find())) score -= 20;
        if (FORECAST_COMPARISON_CONTEXT.matcher(actionWindow).find()) score -= 16;
        if (HISTORICAL_MOVEMENT_CONTEXT.matcher(actionWindow).find()) score -= 16;
        if (HISTORICAL_TABLE_CONTEXT.matcher(actionWindow).find()) score -= 16;
        if (GUIDANCE_COMPARISON_TABLE_CONTEXT.matcher(actionWindow).find()) score -= 16;
        if (metric == MetricKind.FREE_CASH_FLOW
                && (FCF_HISTORICAL_TABLE_CONTEXT.matcher(actionWindow).find()
                || FCF_PROJECT_DESCRIPTOR_CONTEXT.matcher(actionWindow).find()
                || HISTORICAL_FCF_CONTEXT.matcher(actionWindow).find())) {
            score -= 16;
        }
        if (metric == MetricKind.CAPEX && (CAPEX_HISTORICAL_TABLE_CONTEXT.matcher(actionWindow).find()
                || (CAPEX_HISTORICAL_MOVEMENT_CONTEXT.matcher(actionWindow).find()
                && !STRONG_GUIDANCE_LANGUAGE.matcher(actionWindow).find()))) score -= 20;
        return score;
    }

    private static boolean isProspective(MetricSlice slice, MetricKind metric) {
        var actionWindow = actionWindow(slice);
        if (EXCLUDED_CONTEXT.matcher(actionWindow).find()
                || FORECAST_COMPARISON_CONTEXT.matcher(actionWindow).find()
                || HISTORICAL_MOVEMENT_CONTEXT.matcher(actionWindow).find()
                || HISTORICAL_TABLE_CONTEXT.matcher(actionWindow).find()
                || GUIDANCE_COMPARISON_TABLE_CONTEXT.matcher(actionWindow).find()
                || BALANCE_SHEET_CONTEXT.matcher(actionWindow).find()
                || (metric == MetricKind.FREE_CASH_FLOW
                && (FCF_HISTORICAL_TABLE_CONTEXT.matcher(actionWindow).find()
                || FCF_PROJECT_DESCRIPTOR_CONTEXT.matcher(actionWindow).find()
                || HISTORICAL_FCF_CONTEXT.matcher(actionWindow).find()))
                || (metric == MetricKind.CAPEX
                && (CAPEX_HISTORICAL_TABLE_CONTEXT.matcher(actionWindow).find()
                || (CAPEX_HISTORICAL_MOVEMENT_CONTEXT.matcher(actionWindow).find()
                && !STRONG_GUIDANCE_LANGUAGE.matcher(actionWindow).find())))
                || (metric == MetricKind.REVENUE
                && (DEFERRED_REVENUE_CONTEXT.matcher(actionWindow).find()
                || ACCOUNTING_ESTIMATE_CONTEXT.matcher(actionWindow).find()
                || CONTRACT_REVENUE_SCHEDULE_CONTEXT.matcher(actionWindow).find()
                || CUSTOMER_REVENUE_STATISTIC_CONTEXT.matcher(actionWindow).find()
                || REVENUE_NON_GUIDANCE_CONTEXT.matcher(actionWindow).find()))) {
            return false;
        }
        if (HISTORICAL_CONTEXT.matcher(actionWindow).find()
                && !withinDistance(slice, EXPECTATION_ACTION, 60)) {
            return false;
        }
        return withinDistance(slice, EXPECTATION_ACTION, 60)
                || withinDistance(slice, GUIDANCE_LANGUAGE, 100)
                || withinDistance(slice, RAISED_ACTION, 100)
                || withinDistance(slice, LOWERED_ACTION, 100)
                || withinDistance(slice, AFFIRMED_ACTION, 100);
    }

    private static boolean withinDistance(MetricSlice slice, Pattern pattern, int maxDistance) {
        var matcher = pattern.matcher(slice.text());
        while (matcher.find()) {
            var distance = slice.metricEnd() < matcher.start()
                    ? matcher.start() - slice.metricEnd()
                    : matcher.end() < slice.metricStart()
                    ? slice.metricStart() - matcher.end()
                    : 0;
            if (distance <= maxDistance) return true;
        }
        return false;
    }

    private static int lastMatchBefore(Pattern pattern, String text, int before) {
        var matcher = pattern.matcher(text);
        var result = -1;
        while (matcher.find() && matcher.start() < before) result = matcher.start();
        return result;
    }

    private static Direction direction(MetricSlice slice) {
        if (withinDistance(slice, RAISED_ACTION, 100)) return Direction.RAISED;
        if (withinDistance(slice, LOWERED_ACTION, 100)) return Direction.LOWERED;
        if (withinDistance(slice, AFFIRMED_ACTION, 100)) return Direction.AFFIRMED;
        return Direction.MENTIONED;
    }

    private static Set<Direction> genericGuidanceDirections(List<String> clauses) {
        var result = EnumSet.noneOf(Direction.class);
        for (var clause : clauses) {
            if (!GUIDANCE_LANGUAGE.matcher(clause).find()) continue;
            collectLocalGuidanceDirection(result, clause, RAISED_ACTION, Direction.RAISED);
            collectLocalGuidanceDirection(result, clause, LOWERED_ACTION, Direction.LOWERED);
            collectLocalGuidanceDirection(result, clause, AFFIRMED_ACTION, Direction.AFFIRMED);
        }
        return result;
    }

    private static void collectLocalGuidanceDirection(
            Set<Direction> target,
            String clause,
            Pattern action,
            Direction direction
    ) {
        var actionMatcher = action.matcher(clause);
        while (actionMatcher.find()) {
            var start = Math.max(0, actionMatcher.start() - 120);
            var end = Math.min(clause.length(), actionMatcher.end() + 180);
            var window = clause.substring(start, end);
            if (GUIDANCE_LANGUAGE.matcher(window).find() && !EXCLUDED_CONTEXT.matcher(window).find()) {
                target.add(direction);
                return;
            }
        }
    }

    private static java.util.Optional<String> firstDirectionalGuidanceClause(List<String> clauses) {
        return clauses.stream()
                .filter(clause -> GUIDANCE_LANGUAGE.matcher(clause).find())
                .filter(CompanyGuidanceParsingPolicy::hasDirectionalAction)
                .map(CompanyGuidanceParsingPolicy::directionalGuidanceWindow)
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    private static String directionalGuidanceWindow(String clause) {
        for (var action : List.of(RAISED_ACTION, LOWERED_ACTION, AFFIRMED_ACTION)) {
            var matcher = action.matcher(clause);
            while (matcher.find()) {
                var start = Math.max(0, matcher.start() - 120);
                var end = Math.min(clause.length(), matcher.end() + 220);
                var window = clause.substring(start, end);
                if (GUIDANCE_LANGUAGE.matcher(window).find() && !EXCLUDED_CONTEXT.matcher(window).find()) {
                    return bounded(window);
                }
            }
        }
        return null;
    }

    private static Stance stance(Set<Direction> directions) {
        var explicit = EnumSet.noneOf(Direction.class);
        explicit.addAll(directions);
        explicit.remove(Direction.MENTIONED);
        if (explicit.size() > 1) return Stance.MIXED;
        if (explicit.contains(Direction.RAISED)) return Stance.RAISED;
        if (explicit.contains(Direction.LOWERED)) return Stance.LOWERED;
        if (explicit.contains(Direction.AFFIRMED)) return Stance.AFFIRMED;
        return Stance.UNCLEAR;
    }

    private static void addExplicitDirection(Set<Direction> target, CompanyGuidanceMetric metric) {
        if (metric != null && metric.direction() != Direction.MENTIONED) target.add(metric.direction());
    }

    private static void addEvidence(Set<String> target, CompanyGuidanceMetric metric) {
        if (metric != null) target.add(metric.text());
    }

    private static boolean hasDirectionalAction(String text) {
        return RAISED_ACTION.matcher(text).find()
                || LOWERED_ACTION.matcher(text).find()
                || AFFIRMED_ACTION.matcher(text).find();
    }

    private static String normalize(String text) {
        return text.replace('\u00a0', ' ')
                .replaceAll("[\\t\\r\\f ]+", " ")
                .replaceAll(" *\\n *", "\\n")
                .trim();
    }

    private static String normalizeClause(String text) {
        return normalize(text).replaceAll("\\s+", " ").trim();
    }

    private static String bounded(String text) {
        var normalized = normalizeClause(text);
        return normalized.length() <= MAX_CLAUSE_CHARACTERS
                ? normalized
                : normalized.substring(0, MAX_CLAUSE_CHARACTERS);
    }

    private enum MetricKind {
        REVENUE("revenue|net sales|total sales|top[- ]line"),
        MARGIN("gross margins?|operating margins?|margin"),
        CAPEX("capex|capital expenditures?|capital spending|property and equipment spending"),
        FREE_CASH_FLOW("free cash flow|\\bfcf\\b");

        private final Pattern pattern;

        MetricKind(String regex) {
            this.pattern = Pattern.compile("(?:" + regex + ")", Pattern.CASE_INSENSITIVE);
        }
    }

    private record Candidate(
            int score,
            int clauseIndex,
            int metricIndex,
            String text,
            CompanyGuidanceMetricValue value,
            Direction direction
    ) {
        boolean structured() {
            return value != null && value.structured();
        }
    }

    private record MetricSlice(String text, int metricStart, int metricEnd) {
    }

}
