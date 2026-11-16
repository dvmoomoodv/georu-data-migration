# 기여 안내

georu 에 관심을 가져 주셔서 고맙습니다. 이슈·문서 수정·코드 기여 모두 환영합니다.

## 시작 전에

- 버그 신고와 기능 제안은 GitHub Issue 로 먼저 올려 주세요. 큰 변경은 구현 전에 이슈에서 방향을 맞추는 것이 좋습니다.
- 보안 취약점은 공개 이슈로 올리지 말고 [SECURITY.md](SECURITY.md) 의 절차를 따라 주세요.
- 모든 참여자는 [행동 강령](CODE_OF_CONDUCT.md)을 지켜 주세요.

## 개발 환경

- JDK 17, Docker (Compose v2)
- 구조: 라이브러리 모듈(`georu-core`, `georu-api`, `georu-ui`, `georu-spring-boot-autoconfigure`, `georu-spring-boot-starter`, `georu-test`)과 예제 앱(`examples/demo`). 엔진 변경은 라이브러리에, 예제·로컬 환경 변경은 `examples/demo` 에 넣는다.
- 로컬 DB: `cd examples/demo && cp docker/.env.example docker/.env && ./run-scripts/run-docker.sh db`
- 앱 실행: `./gradlew -p examples/demo bootRunDocker` (호스트) 또는 `examples/demo/run-scripts/run-docker.sh up` (컨테이너)
- 테스트: `./gradlew test` (라이브러리 전 모듈) 와 `./gradlew -p examples/demo test` (데모 앱). DB 없이 실행되는 단위·H2 테스트

## 코드 작성 규칙

- 주변 코드의 이름·주석 밀도·관용구를 따릅니다. 주석과 문서는 한국어로 씁니다.
- 이름은 타겟(TOBE) 테이블 기준입니다. Job 키는 타겟 테이블명 소문자입니다.
- 제품 코드는 타겟 데이터를 TRUNCATE·DELETE 하지 않습니다. 적재 방식은 Mapper 쿼리로 정합니다.
- 업체(고객) 전용 코드는 이 저장소에 넣지 않습니다. 업체별 이관은 별도 저장소의 플러그인으로 만듭니다.
- 비밀번호·토큰·내부 주소는 커밋하지 않습니다. 접속 정보는 환경변수로 받습니다. `docker/.env` 는 추적 대상이므로 데모 기본값만 두고, 실제 비밀번호·토큰은 넣지 않습니다.
- 새 기능에는 테스트를 함께 넣어 주세요. 계층 의존 규칙은 ArchUnit 테스트가 검사합니다.
- 이관 설계 도구의 규칙(원천 매핑 · 관계 추정 · 정의서 초안 · 업무 패키지 제안)을 바꾸면 `georu-codegen` 의 요약 골든이 달라집니다. 의도한 변경이면 `./gradlew :georu-codegen:test -PupdateGolden` 로 골든(`src/test/resources/fixtures/anon/golden/`)을 갱신하고, PR 에 어떤 줄이 왜 바뀌었는지 한 줄 적어 주세요. 골든 diff 가 없으면 그 규칙이 고정 데이터에 걸리지 않는 것이니 `fixtures/anon/README.md` 를 보고 입력을 더합니다.

## 운영 UI 디자인 점검 (Hallmark · Impeccable)

운영 UI(`georu-ui`) 화면을 고칠 때 "AI 가 만든 티"가 나지 않는지 두 도구로 본다. 어느 PC 에서나 같은 판을 쓰도록 스크립트가 고정한 커밋으로 설치한다.

```
tools/dev/design-skills.sh install           # .claude/skills/{hallmark,impeccable} (git 에는 안 들어간다) + 렌더용 puppeteer-core
tools/dev/design-skills.sh audit             # 떠 있는 데모 앱(기본 http://localhost:8083)의 22 화면을 렌더링해 검사
tools/dev/design-skills.sh audit http://localhost:8082   # 소스로 띄운 앱 (./gradlew -p examples/demo bootRunDocker)
tools/dev/design-skills.sh detect            # Impeccable 만, 소스 정적 검사 (앱 불필요)
```

- 필요: git, Node 18+, Chrome (렌더링). 결과 · 화면 사진은 저장소 밖 `${TMPDIR}/georu-design` (바꾸려면 `DESIGN_WORK`).
- 설치하면 Claude Code 가 `.claude/skills/` 의 두 스킬을 바로 쓴다 (세션을 다시 열면 목록에 보인다). Hallmark 는 이름을 불러야 기존 화면에 쓴다: "Hallmark 로 운영 UI audit", `/hallmark audit georu-ui/src/main/resources/templates/ui`.
- 기준은 **polish**: 배치 · 색 체계(GitHub Primer 풍)는 그대로 두고 검사기가 짚은 것만 고친다. 결과는 `ui.css` 첫 줄의 Hallmark 도장에 적는다 (고친 규칙 · 바꾸지 않은 것과 이유).
- 색은 `:root` 토큰으로만 쓴다 (`var(--…)`). 새 색이 필요하면 토큰을 더한다. 입력칸 테두리는 `--field-line`(흰 바탕 대비 3:1), 포커스는 `--focus`.
- 바꾸지 않는 지적: 63 (가운뎃점 `·` 은 한국어 나열 부호이고 제품 문구 전체의 표기). 템플릿 파일을 레이아웃 없이 따로 읽어 생기는 지적(26a · 34 · 27 · F23 · render)은 실제 화면과 무관하다. 간격 4px 단위(24b) · 컨트롤 높이(39b) · 좁은 화면 줄바꿈(49a · 49b · F25)은 배치를 바꾸는 일이라 polish 에서 하지 않는다.
- 판을 올리려면 스크립트의 `HALLMARK_REF` · `IMPECCABLE_REF` · `IMPECCABLE_CLI` 를 바꾸고 `install` 을 다시 돌린다.

## Pull Request

1. 저장소를 포크하고 `main` 에서 브랜치를 만듭니다 (`feat/…`, `fix/…`, `docs/…`).
2. 변경 후 `./gradlew test` 가 통과하는지 확인합니다.
3. PR 설명에 무엇을, 왜 바꿨는지와 확인 방법을 적습니다. 관련 이슈가 있으면 연결합니다.
4. 리뷰 의견을 반영해 머지합니다.

기여한 코드는 이 프로젝트의 [Apache License 2.0](LICENSE) 으로 배포됩니다.
