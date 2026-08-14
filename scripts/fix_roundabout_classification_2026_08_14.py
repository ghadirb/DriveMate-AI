from pathlib import Path
import re

p = Path('app/src/main/java/ai/drivemate/MainActivity.java')
s = p.read_text(encoding='utf-8')
pattern = re.compile(r'''        else if \(lower\.contains\("uturn"\).*?\n        else if \(text\.contains\("ادامه"\).*?;''', re.DOTALL)
replacement = '''        else if (lower.contains("uturn") || lower.contains("u-turn") || text.contains("دور بزنید")) lastInstruction = "make_u_turn";
        else if (text.contains("ادامه") && !text.contains("خروجی") && !text.contains("بمانید")) lastInstruction = "continue_route";'''
if not pattern.search(s):
    raise SystemExit('roundabout classification repair marker not found')
p.write_text(pattern.sub(replacement, s, count=1), encoding='utf-8')
print('fixed roundabout classification chain')
