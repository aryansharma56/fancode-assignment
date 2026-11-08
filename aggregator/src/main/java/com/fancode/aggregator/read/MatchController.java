package com.fancode.aggregator.read;

import com.fancode.aggregator.read.MatchViews.CommentaryView;
import com.fancode.aggregator.read.MatchViews.MatchDetail;
import com.fancode.aggregator.read.MatchViews.MatchSummary;
import com.fancode.aggregator.read.MatchViews.ProvidersView;
import com.fancode.aggregator.read.MatchViews.ScoreView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/matches")
public class MatchController {

    private final MatchViewService views;

    public MatchController(MatchViewService views) {
        this.views = views;
    }

    @GetMapping
    public List<MatchSummary> list() {
        return views.listMatches();
    }

    @GetMapping("/{id}")
    public MatchDetail match(@PathVariable String id) {
        return views.match(id);
    }

    @GetMapping("/{id}/score")
    public ScoreView score(@PathVariable String id) {
        return views.score(id);
    }

    @GetMapping("/{id}/commentary")
    public CommentaryView commentary(@PathVariable String id) {
        return views.commentary(id);
    }

    /** Debug/ops view: what each provider has sent us and whether polling is healthy. */
    @GetMapping("/{id}/providers")
    public ProvidersView providers(@PathVariable String id) {
        return views.providers(id);
    }
}
