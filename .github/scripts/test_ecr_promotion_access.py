import copy
import json
import subprocess
import unittest
from unittest.mock import patch

from ensure_ecr_promotion_access import SID, ensure_access

ROLE = "arn:aws:iam::359590772493:role/github-oidc-provider-Role-F6vlRCdf8Hkx"
REPOSITORY = "cdk-hnb659fds-container-assets-751533618173-us-east-1"
BOOTSTRAP_POLICY = {
    "Version": "2012-10-17",
    "Statement": [{
        "Sid": service + "ECRImageRetrievalPolicy",
        "Effect": "Allow",
        "Principal": {"Service": service.lower() + ".amazonaws.com"},
        "Action": ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"],
        "Condition": {"StringLike": {"aws:sourceArn": "arn:aws:lambda:us-east-1:751533618173:*"}},
    } for service in ["Lambda", "EmrServerless"]],
}


class PromotionPolicyTest(unittest.TestCase):
    def apply(self, policy):
        with patch("ensure_ecr_promotion_access.subprocess.run") as run:
            run.return_value.stdout = json.dumps(policy)
            ensure_access(REPOSITORY, ROLE)
            if run.call_count == 1:
                return policy, False
            args = run.call_args.args[0]
            self.assertEqual(args[:5], ["aws", "ecr", "set-repository-policy", "--repository-name", REPOSITORY])
            return json.loads(args[args.index("--policy-text") + 1]), True

    def test_preserves_bootstrap_and_unrelated_statements(self):
        original = copy.deepcopy(BOOTSTRAP_POLICY)
        original["Statement"].append({"Effect": "Deny", "Principal": "*", "Action": "ecr:DeleteRepository"})
        updated, changed = self.apply(original)
        self.assertTrue(changed)
        self.assertEqual(updated["Version"], original["Version"])
        self.assertEqual(updated["Statement"][:-1], original["Statement"])
        grant = updated["Statement"][-1]
        self.assertEqual(grant["Principal"], {"AWS": ROLE})
        self.assertEqual(set(grant["Action"]), {"ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"})

    def test_rerun_does_not_write(self):
        updated, _ = self.apply(BOOTSTRAP_POLICY)
        self.assertFalse(self.apply(updated)[1])

    def test_replaces_stale_role_grant(self):
        original = copy.deepcopy(BOOTSTRAP_POLICY)
        original["Statement"].append({"Sid": SID, "Principal": {"AWS": "old-role"}, "Action": "ecr:*"})
        updated, _ = self.apply(original)
        self.assertEqual(len(updated["Statement"]), len(original["Statement"]))
        self.assertNotIn("old-role", json.dumps(updated))

    def test_read_failure_never_writes(self):
        with patch("ensure_ecr_promotion_access.subprocess.run", side_effect=subprocess.CalledProcessError(1, "aws")) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                ensure_access(REPOSITORY, ROLE)
            self.assertEqual(run.call_count, 1)

    def test_malformed_policy_never_writes(self):
        with patch("ensure_ecr_promotion_access.subprocess.run") as run:
            run.return_value.stdout = "not-json"
            with self.assertRaises(json.JSONDecodeError):
                ensure_access(REPOSITORY, ROLE)
            self.assertEqual(run.call_count, 1)

    def test_rejects_account_or_wildcard_principal(self):
        for role in ["arn:aws:iam::359590772493:root", "*", ROLE + "*"]:
            with self.subTest(role=role), patch("ensure_ecr_promotion_access.subprocess.run") as run:
                with self.assertRaises(ValueError):
                    ensure_access(REPOSITORY, role)
                run.assert_not_called()

    def test_write_failure_propagates(self):
        with patch("ensure_ecr_promotion_access.subprocess.run") as run:
            run.side_effect = [subprocess.CompletedProcess([], 0, json.dumps(BOOTSTRAP_POLICY)), subprocess.CalledProcessError(1, "aws")]
            with self.assertRaises(subprocess.CalledProcessError):
                ensure_access(REPOSITORY, ROLE)


if __name__ == "__main__":
    unittest.main()
