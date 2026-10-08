package it.unimib.datai.nanofaas.containerdeployment;

/** A preexisting name is never evidence that the resource belongs to this creation. */
public final class ContainerNameConflictException extends IllegalStateException {
    public ContainerNameConflictException(String name, Throwable cause) {
        super("Container name already occupied: " + name, cause);
    }
}
