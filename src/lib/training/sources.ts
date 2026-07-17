export const sourceRefs = {
	cdcActivityGuidelines: 'cdc-adult-activity-guidelines',
	mayoInjuryAvoidance: 'mayo-running-injury-avoidance',
	mayoBeginnerRunWalk: 'mayo-beginner-run-walk',
	nhsCouchTo5k: 'nhs-couch-to-5k',
	mayoTaper: 'mayo-taper-guidance',
	tanakaHeartRate: 'tanaka-adult-max-heart-rate',
	gulatiHeartRate: 'gulati-women-max-heart-rate',
	ahaTargetHeartRates: 'aha-target-heart-rates',
	rei5kTraining: 'rei-5k-training',
	rei10kTraining: 'rei-10k-training',
	reiHalfMarathon: 'rei-half-marathon-training',
	reiMarathonTraining: 'rei-marathon-training',
	rrcaRunnerGuidance: 'rrca-runner-guidance',
	niamsSportsInjury: 'niams-sports-injury'
} as const;

export type TrainingSourceRef = (typeof sourceRefs)[keyof typeof sourceRefs];

export const trainingSourceDetails: Record<
	TrainingSourceRef,
	{ label: string; url: string; rule: string; limits?: string }
> = {
	[sourceRefs.cdcActivityGuidelines]: {
		label: 'CDC adult activity guidelines',
		url: 'https://www.cdc.gov/physical-activity-basics/guidelines/adults.html',
		rule: 'General aerobic-activity guidance informs recovery context; it does not define the race schedule.'
	},
	[sourceRefs.mayoInjuryAvoidance]: {
		label: 'Mayo Clinic Health System running injury guidance',
		url: 'https://www.mayoclinichealthsystem.org/hometown-health/speaking-of-health/how-can-i-become-a-better-runner-and-avoid-injury',
		rule: 'Weekly increases are guarded, hard runs are separated, and pain makes rest/review the primary proposal; future changes still require confirmation.'
	},
	[sourceRefs.mayoBeginnerRunWalk]: {
		label: 'Mayo Clinic beginner 5K run/walk guidance',
		url: 'https://www.mayoclinic.org/healthy-lifestyle/fitness/in-depth/5k-run/art-20050962',
		rule: 'Beginning runners may alternate running, walking, and rest while building consistency.',
		limits: 'The schedule is general guidance and does not diagnose readiness or injury.'
	},
	[sourceRefs.nhsCouchTo5k]: {
		label: 'NHS Couch to 5K running plan',
		url: 'https://www.nhs.uk/better-health/get-active/get-running-with-couch-to-5k/couch-to-5k-running-plan/',
		rule: 'The foundation phase reproduces the nine-week, three-session run/walk schedule.',
		limits:
			'Completion creates an observed baseline for confirmation; it does not prove race readiness.'
	},
	[sourceRefs.mayoTaper]: {
		label: 'Mayo Clinic Health System taper guidance',
		url: 'https://www.mayoclinichealthsystem.org/hometown-health/speaking-of-health/designing-your-taper-to-maximize-your-potential-on-race-day',
		rule: 'Planned distance decreases during the final weeks before the goal event.'
	},
	[sourceRefs.tanakaHeartRate]: {
		label: 'Tanaka adult max-heart-rate estimate',
		url: 'https://pubmed.ncbi.nlm.nih.gov/?term=11153730',
		rule: 'Male and unspecified estimates use 208 - 0.7 x age as an editable starting point.',
		limits: 'Adult population estimate, not a measured individual maximum.'
	},
	[sourceRefs.gulatiHeartRate]: {
		label: 'Gulati women-specific max-heart-rate estimate',
		url: 'https://www.ovid.com/journals/circ/fulltext/10.1161/circulationaha.110.939249~heart-rate-response-to-exercise-stress-testing-in',
		rule: 'Female estimates use 206 - 0.88 x age as an editable starting point.',
		limits: 'Adult population estimate, not a measured individual maximum.'
	},
	[sourceRefs.ahaTargetHeartRates]: {
		label: 'American Heart Association target-heart-rate guidance',
		url: 'https://www.heart.org/en/healthy-living/fitness/fitness-basics/target-heart-rates',
		rule: 'Percentage bands seed editable zone floors after maximum heart rate is estimated.',
		limits: 'Heart rate is descriptive context and never plan-adjustment authority by itself.'
	},
	[sourceRefs.rei5kTraining]: {
		label: 'REI 5K training guidance',
		url: 'https://www.rei.com/learn/expert-advice/road-running-5k-training-plan.html',
		rule: 'Plans retain rest days and limit weekly distance increases.'
	},
	[sourceRefs.rei10kTraining]: {
		label: 'REI 10K training schedule',
		url: 'https://www.rei.com/dam/ea_10k_road_run_training_plan.pdf',
		rule: 'Plans use gradual endurance progression; speedwork is not generated.'
	},
	[sourceRefs.reiHalfMarathon]: {
		label: 'REI half marathon training guidance',
		url: 'https://www.rei.com/learn/expert-advice/how-to-run-half-marathon.html',
		rule: 'Plans progress the long run, retain rest, and schedule 3 to 4 run days.'
	},
	[sourceRefs.reiMarathonTraining]: {
		label: 'REI marathon training guidance',
		url: 'https://www.rei.com/learn/expert-advice/training-for-your-first-marathon.html',
		rule: 'Marathon plans require a stronger weekly and long-run baseline than shorter goals.'
	},
	[sourceRefs.rrcaRunnerGuidance]: {
		label: 'Road Runners Club of America runner guidance',
		url: 'https://www.rrca.org/education/for-runners/',
		rule: 'Plans retain recovery days and do not add missed distance to later workouts.'
	},
	[sourceRefs.niamsSportsInjury]: {
		label: 'NIAMS sports injury guidance',
		url: 'https://www.niams.nih.gov/health-topics/sports-injuries/diagnosis-treatment-and-steps-to-take',
		rule: 'Pain makes rest and review the primary recommendation; runway does not diagnose or silently change future work.'
	}
};
