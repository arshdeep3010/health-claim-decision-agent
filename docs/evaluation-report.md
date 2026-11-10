# Evaluation report (generated)

This file is produced by the evaluation harness (`EvaluationHarness`) from the
labeled synthetic dataset (`src/main/resources/synthetic/dataset.json`). It is a
real experiment output, not hand-written numbers. Regenerate it any time with:

```bash
# writes evaluation-report.md
./mvnw -q -Dtest=EvaluationHarnessTest test     # also writes target/evaluation/rule-only.md
# or run the app with the eval profile:
java -jar target/health-claim-decision-agent-0.1.0-SNAPSHOT.jar --spring.profiles.active=eval
```

## Arms

- **RULE_ONLY** (shown below): the deterministic rule engine only — fully offline,
  no AI, zero cost. Because the dataset labels are rule-grounded, this arm reproduces
  them exactly (accuracy 1.000); it therefore doubles as a **consistency check** that
  the dataset and the rule engine agree.
- **LLM_ONLY / HYBRID / MERCURY**: supported by the harness via a pluggable predictor,
  but require a configured AI provider (`MERCURY_PROVIDER=openrouter` + key). They are
  intentionally not run in CI (no paid APIs). Their value is measuring where the AI
  agrees/diverges from the rules on the ALLOW cases.

---

# Evaluation report — arm: RULE_ONLY

> SYNTHETIC DATA ONLY. Generated from the labeled dataset (`synthetic/dataset.json`) by the evaluation harness.

- Claims evaluated: **30**
- Accuracy: **1.000** (30/30)
- Macro-F1: **1.000**
- False approval rate: **0.000**
- False rejection rate: **0.000**
- Human-review rate: **0.500**
- Avg decision latency: **0.120 ms**
- Estimated AI cost: **$0.0000**

## Per-class metrics

| Decision | Precision | Recall | F1 | Support |
|----------|-----------|--------|----|---------|
| APPROVE | 1.000 | 1.000 | 1.000 | 7 |
| REJECT | 1.000 | 1.000 | 1.000 | 8 |
| HUMAN_REVIEW | 1.000 | 1.000 | 1.000 | 15 |

## Dataset distribution

7 APPROVE · 8 REJECT · 15 HUMAN_REVIEW across 5 synthetic policies — covering covered
procedures, expired policy, excluded procedures (cosmetic/experimental/dental/
infertility), initial and pre-existing waiting periods, over-limit claims, missing
diagnosis/procedure, conflicting facts, and unknown pre-existing status. See the full
per-claim table in `target/evaluation/rule-only.md` after running the harness.
