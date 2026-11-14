"""The pane ids the app's Kotlin declares, for the tests that check every pane has its budgets and parts
(tools/perf/test/test_grade.py, tools/size/test/test_apksize.py). Reads source files only; changes nothing."""
import os
import re


def pane_ids_in(src):
    """The pane ids the Kotlin under [src] declares with `override val id = …`: a literal, the file's own
    `const val ID = "…"`, or another class's (`CameraPane.ID`). An id that resolves to nothing fails, so a
    new form can't slip past the budget check (#117: the camera pane's constant did)."""
    texts = []
    for d, _, fs in os.walk(src):
        for f in fs:
            if f.endswith(".kt"):
                with open(os.path.join(d, f)) as fh:
                    texts.append(fh.read())
    consts = {}  # class name -> its companion's ID
    for t in texts:
        for cls, body in re.findall(r'class (\w+)\b(.*?)(?=\n\S[^\n]*\bclass \w|\Z)', t, re.S):
            m = re.search(r'const val ID = "([a-z0-9-]+)"', body)
            if m:
                consts[cls] = m.group(1)
    ids = set()
    for t in texts:
        for cls, body in re.findall(r'class (\w+)\b(.*?)(?=\n\S[^\n]*\bclass \w|\Z)', t, re.S):
            for ref in re.findall(r'override val id = ([^\n]+)', body):
                ref = ref.split("//")[0].strip()
                lit = re.fullmatch(r'"([a-z0-9-]+)"', ref)
                if lit:
                    ids.add(lit.group(1))
                    continue
                name = re.fullmatch(r'(?:(\w+)\.)?ID', ref)
                assert name, f"{cls}: a pane id this test can't read: {ref}"
                owner = name.group(1) or cls
                assert owner in consts, f"{cls}: {ref} isn't a const val ID this test can find"
                ids.add(consts[owner])
    return ids
