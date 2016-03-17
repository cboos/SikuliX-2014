package org.sikuli.natives;

/**
 * Wrapper for all Exception which occurs during native command execution.
 *
 * @author tschneck
 *         Date: 9/15/15
 */
public class NativeCommandException extends RuntimeException {
    private final CommandExecutorResult commandExecutorResult;

    public NativeCommandException(String message) {
        super(message);
        this.commandExecutorResult = null;
    }

    public NativeCommandException(String message, CommandExecutorResult commandExecutorResult) {
        super(message);
        this.commandExecutorResult = commandExecutorResult;
    }

    public CommandExecutorResult getCommandExecutorResult() {
        return commandExecutorResult;
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder("[error] ");
        if (super.getMessage() != null) {
            sb.append(super.getMessage());
        }
        if (commandExecutorResult != null) {
            String stout = commandExecutorResult.getStandardOutput();
            if (stout != null && !stout.isEmpty()) {
                sb.append("\n[stout] ").append(stout);
            }
            String errorOutput = commandExecutorResult.getErrorOutput();
            if (errorOutput != null && !errorOutput.isEmpty()) {
                sb.append("\n[errout] ").append(errorOutput);
            }
        }
        return sb.toString();
    }
}
