import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).parents[1] / "classify-deploy-scope.py"
SPEC = importlib.util.spec_from_file_location("classify_deploy_scope", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class DeployScopeClassifierTest(unittest.TestCase):
    def classify(self, **overrides):
        values = dict(
            server=False,
            client=False,
            scripts=False,
            docs=False,
            observability=False,
            compose=False,
            readme=False,
            server_changes="",
        )
        values.update(overrides)
        return MODULE.classify(**values)

    def test_no_changes_is_read_only_verify(self):
        self.assertEqual("verify", self.classify()["scope"])

    def test_docs_and_scripts_never_restart_applications(self):
        self.assertEqual("docs", self.classify(docs=True)["scope"])
        self.assertEqual("scripts", self.classify(scripts=True, docs=True)["scope"])

    def test_single_application_change_is_scoped(self):
        self.assertEqual("server", self.classify(server=True)["scope"])
        self.assertEqual("client", self.classify(client=True)["scope"])

    def test_cross_service_or_runtime_contract_change_fails_full(self):
        self.assertEqual("full", self.classify(server=True, client=True)["scope"])
        self.assertEqual("full", self.classify(observability=True)["scope"])
        self.assertEqual("full", self.classify(compose=True)["scope"])

    def test_persistence_and_build_contract_changes_escalate_server_release(self):
        for path in (
            ">f.st...... pom.xml",
            ">f.st...... bootstrap/src/main/resources/db/migration/V21__example.sql",
            ">f.st...... adapters/src/main/java/io/example/adapter/out/persistence/JdbcExample.java",
            ">f.st...... adapters/src/test/java/io/example/PostgresExampleIntegrationTest.java",
        ):
            with self.subTest(path=path):
                result = self.classify(server=True, server_changes=path)
                self.assertEqual("server", result["scope"])
                self.assertTrue(result["serverRelease"])

    def test_domain_only_change_does_not_trigger_database_release_gate(self):
        result = self.classify(
            server=True,
            server_changes=">f.st...... domain/src/main/java/io/example/Score.java",
        )
        self.assertFalse(result["serverRelease"])


if __name__ == "__main__":
    unittest.main()
