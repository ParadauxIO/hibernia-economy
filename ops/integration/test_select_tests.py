import fnmatch
from pathlib import Path
import subprocess
import tempfile
import unittest

from select_tests import PACKAGE, TEST_ROOT, select, selection_for_base

ROOT = Path(__file__).resolve().parents[2]
PREFIX = str(TEST_ROOT / 'net/democracycraft/inttests')


class SelectionTest(unittest.TestCase):
    def assert_selected(self, result, *classes):
        self.assertEqual(result['mode'], 'affected')
        for name in classes:
            self.assertTrue(any(fnmatch.fnmatchcase(f'{PACKAGE}.{name}', pattern)
                                for pattern in result['filters']), name)

    def test_rest_changes_cover_command_consumers_and_smoke_but_not_expiry(self):
        result = select(['treasury-rest-api/src/main/resources/application.yml'], ROOT)
        self.assert_selected(result, 'restapi.WebhookRetryTest', 'treasury.TransactionExportTest',
                             'lifecycle.RestartTest', 'FirstJoinSeedsBalanceTest')
        self.assertFalse(any(fnmatch.fnmatchcase(f'{PACKAGE}.business.DisbandExpiryTest', p)
                             for p in result['filters']))

    def test_shop_changes_cover_market_consumers(self):
        result = select(['chestshop/src/main/java/example/TransactionService.java'], ROOT)
        self.assert_selected(result, 'chestshop.PersonalShopTest', 'restapi.MarketFirstTradeTest',
                             'treasury.PersonalSalesCommandTest', 'PluginAuthorityTest')

    def test_test_changes_select_package_and_union_with_module_changes(self):
        result = select([f'{PREFIX}/business/DisbandExpiryTest.java',
                         'treasury-rest-api/src/main/java/example/Service.java'], ROOT)
        self.assert_selected(result, 'business.DisbandExpiryTest', 'business.FirmCommandsTest',
                             'restapi.RateLimitTest')

    def test_single_root_test_does_not_match_every_package(self):
        result = select([f'{PREFIX}/ChatRoundTripTest.java'], ROOT)
        self.assertEqual(result['filters'], [f'{PACKAGE}.ChatRoundTripTest'])

    def test_shared_and_unknown_paths_force_full_even_with_narrow_changes(self):
        for path in ['treasury/src/main/java/Transfer.java', 'business/src/main/java/Firm.java',
                     'common/src/main/java/Common.java', 'economy-flyway/src/main/resources/V99.sql',
                     'integration-tests/mock-client/src/main/java/MockPlayer.java',
                     f'{PREFIX}/support/Await.java', f'{PREFIX}/support/AwaitTest.java',
                     'ops/integration/run.sh', '.github/workflows/ci.yml',
                     'treasury-rest-api/build.gradle.kts', 'chestshop/build.gradle.kts',
                     'gradle.properties', 'gradlew', 'realty', 'hibernia-framework',
                     'new-module/src/main/java/New.java']:
            with self.subTest(path=path):
                result = select([f'{PREFIX}/ChatRoundTripTest.java', path], ROOT)
                self.assertEqual(result['mode'], 'full')
                self.assertEqual(result['filters'], [])

    def test_deleted_tests_force_full(self):
        self.assertEqual(select([f'{PREFIX}/restapi/DeletedTest.java'], ROOT)['mode'], 'full')

    def test_docs_do_not_broaden_an_affected_run(self):
        result = select(['wiki/build-and-test.md', f'{PREFIX}/restapi/RateLimitTest.java'], ROOT)
        self.assertEqual(result['filters'], [f'{PACKAGE}.restapi.*'])

    def test_empty_or_docs_only_selection_falls_back_to_full(self):
        for paths in [[], ['README.md']]:
            self.assertEqual(select(paths, ROOT)['mode'], 'full')

    def test_non_pr_and_missing_base_run_everything(self):
        self.assertEqual(selection_for_base('', ROOT)['mode'], 'full')
        self.assertEqual(selection_for_base('not-a-valid-ref', ROOT)['mode'], 'full')

    def test_git_diff_includes_entire_branch_and_deleted_side_of_rename(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args):
                return subprocess.run(['git', *args], cwd=root, check=True,
                                      capture_output=True, text=True).stdout.strip()

            def commit():
                git('add', '.')
                git('-c', 'user.name=Test', '-c', 'user.email=test@example.com',
                    'commit', '-m', 'fixture')

            git('init')
            shared = root / 'common/shared.txt'
            shared.parent.mkdir()
            shared.write_text('shared dependency')
            commit()
            base = git('rev-parse', 'HEAD')
            shared.write_text('changed dependency')
            commit()
            test = root / PREFIX / 'restapi/NewTest.java'
            test.parent.mkdir(parents=True)
            test.write_text('// test')
            commit()
            # Looking only at the last commit would incorrectly narrow this PR.
            self.assertEqual(selection_for_base(base, root)['mode'], 'full')
            base = git('rev-parse', 'HEAD')
            test.rename(test.with_name('RenamedTest.java'))
            commit()
            self.assertEqual(selection_for_base(base, root)['mode'], 'full')


if __name__ == '__main__':
    unittest.main()
