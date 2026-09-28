# rulepack (로컬 개발용 체크아웃 지점)

이 폴더는 `review-rulepack` 저장소(별도 관리, `D:\git\review\review-rulepack`)의 내용을 로컬 개발/테스트 시 참조하기 위한 자리다.
설계서 1.1 원칙에 따라 룰팩은 이 엔진 레포 밖에서 버전 관리되며, 여기에는 내용을 커밋하지 않는다.

## 로컬 개발 시 사용법

```bash
# 원하는 태그로 review-rulepack을 이 폴더에 체크아웃
rm -rf rulepack/*   # (이 README는 유지)
git -C ../review-rulepack archive v0.1.0 | tar -x -C rulepack
```

또는 CLI 실행 시 `--rulepack` 옵션으로 review-rulepack 클론 경로를 직접 가리켜도 된다:

```bash
./gradlew bootRun --args="--repo=/path/to/target-repo --rulepack=/path/to/review-rulepack --config=/path/to/target-repo/.review.yml"
```

## CI(GitHub Actions)에서는

워크플로가 `.review.yml`의 `rulepack: our-org/review-rulepack@vX.Y.Z`를 읽어 해당 태그를 clone한 뒤 그 경로를 `--rulepack`으로 전달한다. 이 로컬 폴더는 관여하지 않는다.
