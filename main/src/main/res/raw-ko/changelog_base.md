### 지도
- 신규: OSM 지도 소스 osm.paws.cz
- 신규: flopp.net의 GPX 파일을 트랙으로 읽기 활성화
- 수정: '경로에 추가' 후 라우팅 기호 누락
- 수정: 추가 지점에 대한 누락된 경로 계산
- 신규: 'Voluntary MF5' OpenAndroMaps 테마 지원 추가
- 신규: GeoJSON 데이터 지원 추가
- Change: Use last known map position as fallback (when no GPS available and "follow my location" inactive)

### 캐시 상세정보
- 신규: 새로운 고급 이미지 갤러리
- 수정: 지점 업데이트 또는 삭제 후 지점 목록에서 위치 복원
- 수정: 새 지점을 만들 때 맨 아래로 이동
- 신규: 지점 사용자 메모에 입력된 변수 인식
- 신규: 어드벤처 랩 링크가 감지되면 미스터리 캐시 세부 정보에 어드벤처 랩 버튼 표시
- 수정: 서버 측 지점에 대해 동기화되지 않은 지점 설명 제거
- 수정: 스캔 후 지점 목록이 업데이트되지 않음

### 일반
- API 레벨 변경(compileSDK 32)
- 일부 종속 라이브러리 업데이트
- 변경: 다른 안드로이드 메커니즘을 사용하여 다운로드 받기(안드로이드 12+와의 더 나은 호환성을 위해)
- 신규: 가져올 때 GPX 파일 이름이 포함된 사전 설정 목록 이름
- 신규: xmlns 네임스페이스 태그를 제공하지 않는 GPX 트랙 파일 가져오기 허용
- 신규: Android 13용 흑백 런처 아이콘 추가
- 신규: 홈 화면에 geocaching.com 회원 상태 표시
- 변경: GPX-가져오기: '알 수 없는' 커넥터에 대한 지오코드로 이름 사용
- 수정: 소유자 검색에서 영구보관된 캐시에 대한 필터링 허용
- 수정: 로그를 게시한 직후 로그북 보기에서 줄바꿈이 누락되는 경우가 있습니다.
- Fix: Preview button displayed only with filter setting "show all" in PQ list