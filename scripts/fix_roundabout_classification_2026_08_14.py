from pathlib import Path

p = Path('app/src/main/java/ai/drivemate/MainActivity.java')
s = p.read_text(encoding='utf-8')
old = '''        else if (lower.contains("uturn") || lower.contains("u-turn") || text.contains("دور بزنید")) lastInstruction = "make_u_turn";
        else if (text.contains("میدان") || lower.contains("roundabout")) {
            // Previously any roundabout step ("وارد میدان شوید و از خروجی ۲ خارج شوید") fell
            // through every one of the checks above and landed on the generic "continue_route"
            // bucket below - which HAS a matching pre-recorded clip, so in economy mode the driver
            // heard the fixed "ادامه مسیر" clip instead of which exit to actually take. Route to a
            // dedicated roundabout clip when the exit number is 1-3 (the only ones recorded);
            // otherwise fall through to real speech instead of ever substituting the generic clip.
            int exitNumber = extractExitNumber(text);
            lastInstruction = exitNumber >= 1 && exitNumber <= 3 ? "roundabout_exit_" + exitNumber : "roundabout_custom";
        }
        // "continue_route" also has a real recorded clip, so it must stay reserved for instructions
        // that are genuinely generic ("مسیر را ادامه دهید") - anything mentioning a specific lane,
        // exit, or keep-left/right direction needs to be actually spoken, not replaced by that clip.
        else if (text.contains("ادامه") && !text.contains("خروجی") && !text.contains("بمانید")) lastInstruction = "continue_route";'''
new = '''        else if (lower.contains("uturn") || lower.contains("u-turn") || text.contains("دور بزنید")) lastInstruction = "make_u_turn";
        else if (text.contains("ادامه") && !text.contains("خروجی") && !text.contains("بمانید")) lastInstruction = "continue_route";'''
if old not in s:
    raise SystemExit('roundabout classification repair marker not found')
p.write_text(s.replace(old, new, 1), encoding='utf-8')
print('fixed roundabout classification chain')
