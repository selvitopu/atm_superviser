package io.github.selvitopu.atmsup.supervisor.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class StreamController {

    private final SseBroadcaster broadcaster;

    public StreamController(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * The live board as server-sent events. A first connection passes {@code lastEventId} from {@code GET /board} so
     * that nothing committed in between is missed; a reconnecting EventSource sends {@code Last-Event-ID}, which wins.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader,
                             @RequestParam(required = false) String lastEventId) {
        return broadcaster.subscribe(lastEventIdHeader, lastEventId);
    }
}
