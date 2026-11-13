package io.github.selvitopu.atmsup.simulator.api;

import io.github.selvitopu.atmsup.simulator.centre.Centre;
import io.github.selvitopu.atmsup.simulator.model.FaultRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The simulator's console API: look at the devices and inject faults. */
@RestController
@RequestMapping("/api")
public class SimulatorController {

    private final Centre centre;

    public SimulatorController(Centre centre) {
        this.centre = centre;
    }

    @GetMapping("/devices")
    public List<DeviceStateDto> devices() {
        return centre.devices();
    }

    @GetMapping("/devices/{id}")
    public DeviceStateDto device(@PathVariable String id) {
        return centre.device(id);
    }

    @PostMapping("/devices/{id}/faults")
    public DeviceStateDto applyFault(@PathVariable String id, @RequestBody FaultRequest request) {
        return centre.applyFault(id, request);
    }

    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        centre.reset();
    }
}
