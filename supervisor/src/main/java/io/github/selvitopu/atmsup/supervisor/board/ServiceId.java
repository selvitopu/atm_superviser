package io.github.selvitopu.atmsup.supervisor.board;

public enum ServiceId {
    SURV("Surveillance"), FD("Flight data"), VOICE("Voice"), REC("Recording"), NET("Network");

    private final String label;

    ServiceId(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
