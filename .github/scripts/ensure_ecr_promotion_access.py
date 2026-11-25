"""Restore the production pull grant after CDK bootstrap updates its ECR policy."""

import argparse
import json
import re
import subprocess

SID = "AllowProductionImagePromotion"


def ensure_access(repository, role_arn):
    if not re.fullmatch(r"arn:aws:iam::\d{12}:role/[\w+=,.@/-]+", role_arn):
        raise ValueError("Expected a specific production IAM role ARN")

    # Bootstrap owns the other statements. Never replace them with a blank policy
    # if reading the current policy fails (including a missing repository policy).
    result = subprocess.run(
        ["aws", "ecr", "get-repository-policy", "--repository-name", repository,
         "--query", "policyText", "--output", "text"],
        check=True, capture_output=True, text=True,
    )
    current = json.loads(result.stdout)
    statements = current["Statement"]
    if not isinstance(statements, list):
        raise ValueError("Expected a list of repository policy statements")

    desired = dict(current)
    desired["Statement"] = [s for s in statements if s.get("Sid") != SID] + [{
        "Sid": SID,
        "Effect": "Allow",
        "Principal": {"AWS": role_arn},
        "Action": ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage",
                   "ecr:GetDownloadUrlForLayer"],
    }]
    if desired == current:
        print("Production image promotion access is already configured.")
        return

    # Only our named statement is replaced, so role changes do not accumulate grants.
    subprocess.run(
        ["aws", "ecr", "set-repository-policy", "--repository-name", repository,
         "--policy-text", json.dumps(desired)],
        check=True, stdout=subprocess.DEVNULL,
    )
    print("Restored production image promotion access.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    parser.add_argument("role_arn")
    args = parser.parse_args()
    ensure_access(args.repository, args.role_arn)
