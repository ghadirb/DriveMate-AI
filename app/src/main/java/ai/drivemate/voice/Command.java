package ai.drivemate.voice;

public class Command {
    public final CommandType type;
    public final String rawText;

    public Command(CommandType type, String rawText) {
        this.type = type;
        this.rawText = rawText;
    }
}
