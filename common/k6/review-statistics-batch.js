// 시나리오 분리: 캐시 Hit / 캐시 Miss / 대량 조회 테스트
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 50,
    duration: '30s',
    thresholds: {
        http_req_duration: ['p(95)<800'],
        http_req_failed: ['rate<0.01']
    }
};

const BASE_URL = 'http://localhost:19091'; // review-service port
const API_ENDPOINT = '/api/reviews/app/statistics';
// TODO: 실제 테스트 시 유효한 토큰으로 교체 필요
const ACCESS_TOKEN = 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwicm9sZSI6IkFETUlOIiwiaWF0IjoxNzU2OTc1Njk0LCJleHAiOjE3NTY5NzkyOTR9.gypCjffFQ4RbH84vtEoL50szDZTFmn8zqlPJogIvAOQ';

// 시나리오별 식당 ID 샘플
const BATCH_IDS = [
    '11111111-aaaa-bbbb-cccc-111111111111', // 캐시 데이터 존재
    '22222222-aaaa-bbbb-cccc-222222222222',
    '33333333-aaaa-bbbb-cccc-333333333333',
    '44444444-aaaa-bbbb-cccc-444444444444',
    '55555555-aaaa-bbbb-cccc-555555555555',
    '66666666-aaaa-bbbb-cccc-666666666666', // 캐시 미존재 (테스트 전에 Redis에서 삭제 권장)
    '77777777-aaaa-bbbb-cccc-777777777777',
    '88888888-aaaa-bbbb-cccc-888888888888',
    '99999999-aaaa-bbbb-cccc-999999999999',
    '87654321-aaaa-bbbb-cccc-aaaaaaaaaaaa'
];

// HIT: 앞의 5개
const CACHE_HIT_IDS = BATCH_IDS.slice(0, 5);

// MISS: 뒤의 5개
const CACHE_MISS_IDS = BATCH_IDS.slice(5);

function postStatistics(ids, scenarioName) {
    const headers = {
        'Content-Type': 'application/json',
        'Authorization': `${ACCESS_TOKEN}`
    };

    const payload = JSON.stringify({
        restaurantIds: ids
    });

    const res = http.post(`${BASE_URL}${API_ENDPOINT}`, payload, { headers });

    let parsed;
    try {
        parsed = JSON.parse(res.body);
    } catch (e) {
        parsed = [];
    }

    check(res, {
        [`[${scenarioName}] status 200`]: (r) => r.status === 200,
        [`[${scenarioName}] response is array`]: () => Array.isArray(parsed),
        [`[${scenarioName}] each item has reviewCount`]: () =>
            Array.isArray(parsed) &&
            parsed.length > 0 &&
            parsed.every((item) => item.reviewCount !== undefined)
    });
    // console.log(`[${scenarioName}] response body: ${res.body}`);
    sleep(1);
}

export default function () {
    postStatistics(CACHE_HIT_IDS, 'CACHE_HIT');
    postStatistics(CACHE_MISS_IDS, 'CACHE_MISS');
    postStatistics(BATCH_IDS, 'BATCH');
}
