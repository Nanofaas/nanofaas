package it.unimib.datai.nanofaas.controlplane.deployment;

/** Opaque, one-use receiver-issued admission identity; never a physical release proof. */
public record ReplicaAdmissionToken(String admissionEpoch) {
    public ReplicaAdmissionToken {
        if (admissionEpoch == null || admissionEpoch.isBlank() || admissionEpoch.length() > 256) {
            throw new IllegalArgumentException("bounded admission identity required");
        }
    }
}
