package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.application.port.in.QueryDynamicPeersUseCase;
import io.macrosquare.research.domain.peer.PeerDiscoveryResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@RestController
public final class DynamicPeerController {

    private final QueryDynamicPeersUseCase query;

    public DynamicPeerController(QueryDynamicPeersUseCase query) {
        this.query = Objects.requireNonNull(query);
    }

    @GetMapping("/api/research/peers/{ticker}")
    public Response peers(
            @PathVariable String ticker,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return Response.from(query.query(ticker, asOf, limit));
    }

    public record Response(
            String status,
            LocalDate asOf,
            Target target,
            int candidateCount,
            List<Peer> peers,
            String methodology
    ) {
        static Response from(PeerDiscoveryResult value) {
            return new Response(
                    value.target() == null ? "collecting" : "ready", value.asOf(),
                    value.target() == null ? null : new Target(
                            value.target().ticker(), value.target().companyName(), value.target().sic(),
                            value.target().sicDescription(), value.target().sectorKey(),
                            value.target().validFrom(), value.target().validTo()),
                    value.candidateCount(), value.peers().stream().map(item -> new Peer(
                            item.ticker(), item.companyName(), item.sic(), item.sicDescription(),
                            item.sectorKey(), item.similarityScore(), item.matchLevel())).toList(),
                    value.methodology());
        }
    }

    public record Target(
            String ticker,
            String companyName,
            int sic,
            String sicDescription,
            String sectorKey,
            LocalDate validFrom,
            LocalDate validTo
    ) {
    }

    public record Peer(
            String ticker,
            String companyName,
            int sic,
            String sicDescription,
            String sectorKey,
            int similarityScore,
            String matchLevel
    ) {
    }
}
