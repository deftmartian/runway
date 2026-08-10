# training sources

Reviewed: 2026-07-18.

runway uses these sources as guardrails for its planning defaults, not as medical advice. The app should explain the plan arithmetic and tradeoffs without presenting its configured boundaries as medical clearance.

The established distance planner's minimum repeatable week (3 km, two runs, and a positive longest run) is an input limitation for distance-based ramp arithmetic. The weekly total, run count, and longest run must describe the same week: the longest run is at least the weekly average, and the remaining runs each have at least 500 m. This is not health advice, a readiness diagnosis, or a claim that shorter or less frequent activity has no value. A runner who does not enter that baseline first records two weeks of open runs so runway can present observed work for explicit baseline confirmation rather than fabricate distance.

## Product heuristics and evidence limits

Sources below support the direction of conservative progression, rest, and tapering. They do not validate every numeric boundary in runway. The following values are explicit, reviewable product heuristics:

- The distance generator caps its ordinary build at 7.5% per week for `finish_healthy` and 10% for other priorities.
- A weekly routine is a runner-selected run-count goal, not a generated training prescription. An entered baseline is context only; routine slots have no distance, duration, pace, load, or progression rule, so no source below is presented as evidence for a particular routine schedule.
- Ramp assessments use 8%, 12%, and 18% internal boundaries. A plan under eight weeks is also outside the generation limit when its baseline is below 55% of the product's goal-volume target. Setup shows the ramp evidence alongside a broader plan assessment: schedule concentration can raise that assessment independently of the calculated ramp. A plan outside the usual recommendation requires confirmation of its exact workout schedule; a plan outside the generation limit is not created.
- Goal-volume targets (weekly/long run) are 14/5 km for 5K, 22/9 km for 10K, 34/18 km for half-marathon, and 58/32 km for marathon. Long-run warning floors are 4, 8, 16, and 28 km. The marathon-specific baseline warning uses 32 km weekly and a 20 km longest recent run.
- A two-run baseline is preserved rather than silently raised. For a half-marathon or marathon, that concentrates the generated weekly distance into two sessions; runway requires the runner to acknowledge that tradeoff before creating the plan. This is a product guardrail, not a claim that three days is appropriate for every runner.
- An established plan preserves the runner's reported repeatable frequency. In race week, the goal event occupies one of those run slots rather than being added on top. The training-only taper budget is scaled to the remaining run slots before it is distributed, so removing a slot does not make each easy run longer. Half-marathon plans remain capped at four run days in this planner. That cap is a product constraint informed by the source range below, not a personalized readiness judgment.
- Every fourth non-taper week applies a 15% cutback to the build target and long run. Taper volume is 50% of peak for a one-week taper, or 60%, 45%, then 40% for successive weeks of a longer taper. These exact percentages are product heuristics; the taper source supports reducing load, not one universal schedule.
- A workout edit is above the default range when it changes more than 10% of the affected week's load, a high change above 15%, and outside the default range above 25%. The workout-relative percentage remains visible as context but does not set the band. New and removed workouts use their added or removed share of existing weekly load; distance-to-duration conversions also receive a separate non-comparability warning. These bands trigger explanation and confirmation; valid user edits remain available.
- Projected week-to-week ramps after an edit use the 8%, 12%, and 18% boundaries above. Mixed-unit weeks and timed activities without duration are marked non-comparable; no distance-to-duration ratio is inferred.
- Plan-versus-actual material thresholds are `max(500 m, 15%)` for distance and `max(5 minutes, 15%)` for duration. Consequence ranking additionally treats a greater-than-10% overrun against its guardrail base, a two-times prescription, and a greater-than-40% shortfall as proposal inputs.
- Consequence previews use explicit product formulas. Overruns propose removing 50% of the excess, with a 1 km or 5 minute minimum. Shortfalls propose removing 25% of the deficit, or 35% after reported hard effort or another recent deviation, with a 500 m or 5 minute minimum. Skips preview 20% of the prescription, or 30% after hard effort; an on-target hard effort previews 15%. Unplanned activity previews 50% of its load, or 60% after hard effort. Applying a reduction never lowers a distance target below 500 m or a timed target below 10 minutes. These are editable decision defaults, not medical advice or physiological evidence.
- Repeated-shortfall advice considers prior shortened or skipped runs in the preceding 28 days. A run is called a repeated skip only when a prior run was also skipped; a prior shortened run alone is not renamed as a missed run.
- Baseline learning schedules two open runs per week for two weeks. It prescribes neither distance nor duration; accepted results remain observations until the runner confirms a repeatable baseline.
- Editable run prescriptions remain easy effort. The editor does not accept arbitrary effort labels because pace, threshold, interval intensity, and their distinct load effects are not modeled. Rest uses `rest`; goal events retain `race` outside the workout editor.
- Default heart-rate zone floors are 60%, 70%, 80%, and 90% of the selected maximum heart rate. These percentages are editable display defaults and do not drive plan or consequence decisions.
- Training arithmetic rounds an exact half upward when a generated load, decision adjustment, or displayed one-decimal percentage reaches its final stored/presented unit. This is a stability rule for reproducible plans and previews, not a physiological claim.

These values are not diagnoses, physiological measurements, or universal safety boundaries. `Within default` means only that the arithmetic stays inside runway's configured default; it does not mean that running is medically safe for an individual. `Unsupported` means that runway will not generate that distance phase as a default, not that the app has diagnosed danger. Changing a boundary requires domain tests, copy review, and an update here; heart-rate data never changes these assessments by itself.

## Mayo Clinic beginner run/walk guidance

- Source: Mayo Clinic, "5K run: 7-week training schedule for beginners"
- URL: https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/5k-run/art-20050962
- Relevant claims: Beginner schedules can combine running, walking, and rest while building gradually.
- Product rule: baseline learning keeps two selected run days per week for two weeks but leaves each run open. The runner records what happened without a prescribed distance or duration.
- Limits: The Mayo schedule is general guidance. runway's open observation phase is not a reproduced Mayo plan or a medical assessment.

## CDC adult activity guidelines

- Source: CDC, "Adult Activity: An Overview"
- URL: https://www.cdc.gov/physical-activity-basics/guidelines/adults.html
- Relevant claim: Adults should get at least 150 minutes of moderate-intensity aerobic activity per week, or 75 minutes vigorous, plus muscle-strengthening activity on at least 2 days per week.
- Product rule: runway treats aerobic consistency as part of a healthy plan. Strength work is a future module and should not be implied by generated workouts until it exists.
- Limits: This is broad public-health guidance, not a race-specific training plan.

## CDC physical activity intensity

- Source: CDC, "How to Measure Physical Activity Intensity"
- URL: https://www.cdc.gov/physical-activity-basics/measuring/index.html
- Relevant claims: Physical activity intensity affects heart rate and breathing. Moderate activity allows talking but not singing; vigorous activity makes breathing hard and fast.
- Product rule: runway displays heart-rate data as descriptive context. It does not infer that a run felt hard or change the plan from heart rate alone because no reviewed threshold rule is recorded here.
- Limits: Intensity guidance is general. It does not personalize zones for a specific athlete.

## American Heart Association target heart rates

- Source: American Heart Association, "Target Heart Rates Chart"
- URL: https://www.heart.org/en/healthy-living/fitness/fitness-basics/target-heart-rates
- Relevant claims: Age can be used to estimate maximum heart rate, commonly around 220 minus age, and target heart-rate ranges can help estimate exercise intensity.
- Product rule: runway uses age only to seed editable heart-rate zones. Users can override max heart rate and zone floors in settings.
- Limits: Age-based max heart rate is an estimate. It can be wrong for an individual and should not override symptoms, pain, or qualified medical guidance.

## Age and sex heart-rate estimates

- Sources: Tanaka, Monahan, and Seals, "Age-predicted maximal heart rate revisited"; Gulati et al., "Heart Rate Response to Exercise Stress Testing in Asymptomatic Women"; American College of Cardiology summary, "The Heart Responds Differently to Exercise in Men vs. Women"
- URLs: https://pubmed.ncbi.nlm.nih.gov/?term=11153730, https://www.ovid.com/journals/circ/fulltext/10.1161/circulationaha.110.939249~heart-rate-response-to-exercise-stress-testing-in, https://www.acc.org/about-acc/press-releases/2014/03/27/12/29/allison-peak-hr-pr
- Relevant claims: Tanaka et al. describe `208 - 0.7 x age` as a healthy-adult maximum-heart-rate estimate. Gulati et al. report a women-specific peak-heart-rate estimate of `206 - 0.88 x age`, lower than the older `220 - age` estimate.
- Product rule: runway asks for sex only in the settings training profile as an optional input for estimates. Female uses the Gulati estimate; male and not specified use the Tanaka estimate. Users can override the estimated max heart rate and zone floors.
- Limits: These are population estimates, not measured individual zones. Recorded heart-rate peaks, perceived effort, symptoms, medication, fitness history, and clinical guidance can matter more than either formula.

## Mayo Clinic Health System running injury guidance

- Source: Mayo Clinic Health System, "Become a better runner, avoid injury"
- URL: https://www.mayoclinichealthsystem.org/hometown-health/speaking-of-health/how-can-i-become-a-better-runner-and-avoid-injury
- Relevant claims: New runners should generally run 3 to 4 times per week, increase slowly, avoid increasing mileage more than 10% per week, include at least one easy/rest day after every heavy day, and stop if pain affects gait or does not improve early in the run.
- Product rule: runway caps its default weekly ramp near 10%, presents steeper calculated ramps as above default, high increase, or unsupported, and spaces hard or long work away from each other.
- Limits: The 10% rule is treated as a conservative heuristic, not a law. User baseline and recent history still matter.

## Mayo Clinic Health System taper guidance

- Source: Mayo Clinic Health System, "How tapering maximizes your potential on marathon day"
- URL: https://www.mayoclinichealthsystem.org/hometown-health/speaking-of-health/designing-your-taper-to-maximize-your-potential-on-race-day
- Relevant claims: Tapering reduces training load before a key race. The article cites a two-week taper with volume reduced roughly 41-60% while frequency/intensity remain similar, and gives longer taper ranges for longer race distances.
- Product rule: runway adds taper weeks near the target date, reducing volume while keeping familiar workout rhythm.
- Limits: Exact taper size depends on race length and runner history. The distance phase keeps this conservative.

## REI half marathon training guidance

- Source: REI Expert Advice, "How to Train for a Half Marathon"
- URL: https://www.rei.com/learn/expert-advice/how-to-run-half-marathon.html
- Relevant claims: Half-marathon preparation commonly takes 8 to 12 weeks, includes 3 to 4 running days per week, includes rest/recovery days, progresses slowly, and may be finishable after training long runs up to at least 10 miles twice.
- Product rule: runway supports 3 to 4 run days for half-marathon plans, keeps rest visible, and uses long-run progression as a key readiness signal.
- Limits: REI is a reputable outdoor education source, not a medical authority.

## REI 5K training guidance

- Source: REI Expert Advice, "5K Training Plan for Beginners"
- URL: https://www.rei.com/learn/expert-advice/road-running-5k-training-plan.html
- Relevant claims: A 5K plan should include an initial physical assessment, rest days, cross-training, warmups/cooldowns, and a gradual build into event week. The sample plan uses short easy runs and a long run up to about 60 minutes.
- Product rule: runway allows 5K as a shorter race goal, keeps rest days visible, and treats long-run readiness as a smaller target than longer races.
- Limits: The REI sample includes speed and threshold sessions. runway does not generate those because the current product is intentionally conservative and does not yet model pace zones.

## REI 10K training guidance

- Source: REI printable plan, "10K Road Race Training Schedule"
- URL: https://www.rei.com/dam/ea_10k_road_run_training_plan.pdf
- Relevant claims: The 10K plan is a 10-week program with easy runs, long runs, rest, cross-training, and harder sessions.
- Product rule: runway allows 10K as a goal, uses a longer long-run target than 5K, and keeps the generated distance plan easy/rest-focused unless later pace-zone behavior is implemented.
- Limits: The source includes structured speed/threshold work. runway does not expose speedwork until it can explain and persist the added load clearly.

## REI marathon training guidance

- Source: REI Expert Advice, "Training For a Marathon: How To Prepare"
- URL: https://www.rei.com/learn/expert-advice/training-for-your-first-marathon.html
- Relevant claims: A first marathon needs a longer base and more serious preparation than shorter races. The article recommends at least a year of consistent running before a first marathon and frames the work around endurance, recovery, fueling, and qualified healthcare guidance.
- Product rule: runway allows marathon as a goal but should clearly mark the ramp as unsupported when baseline, available weeks, or long-run readiness do not fit the product's generation limits.
- Limits: runway is a conservative planner, not a marathon coaching program. It must not imply that a short generated plan makes an underprepared marathon attempt safe.

## Road Runners Club of America runner guidance

- Source: Road Runners Club of America, "For Runners"
- URL: https://www.rrca.org/education/for-runners/
- Relevant claims: Beginners should keep the long game in mind, use recovery days as needed, run-walk when appropriate, avoid running every day, and treat consistency as more important than intensity.
- Product rule: runway uses factual language and exposes only decisions that can be previewed and persisted. Keep, reduce-next, rest-next, repeat-prescription, and explicit week rebalancing are stored plan decisions; none apply silently.
- Limits: This is general coaching guidance, not individualized medical advice.
