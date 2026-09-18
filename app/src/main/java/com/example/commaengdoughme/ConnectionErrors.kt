package com.example.commaengdoughme

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Only app-owned messages reach the HUD; exception text may contain credentials. */
object ConnectionErrors {
    fun network(error: Exception): String = when (error) {
        is UnknownHostException -> "DNS 오류 · 안경의 Wi-Fi와 인터넷 연결을 확인하세요"
        is SocketTimeoutException -> "응답 시간 초과 · 네트워크를 확인한 뒤 다시 보내세요"
        is SSLException -> "TLS 보안 연결 실패 · 안경의 날짜·시간과 네트워크를 확인하세요"
        is ConnectException -> "서버 접속 실패 · 안경의 인터넷 연결을 확인하세요"
        is IOException -> "통신 중단 · 네트워크를 확인한 뒤 다시 보내세요"
        else -> "요청 처리 실패 · 설정을 확인한 뒤 다시 보내세요"
    }

    fun http(status: Int): String = when (status) {
        400, 422 -> "요청 거부 · 모델 ID와 이미지·JSON 응답 지원 여부를 확인하세요"
        401 -> "인증 실패 · 이 Provider의 API 키를 다시 등록하세요"
        403 -> "접근 거부 · API 키의 권한·프로젝트·지역 제한을 확인하세요"
        404 -> "모델을 찾을 수 없음 · 모델 ID와 접근 권한을 확인하세요"
        413 -> "전송 이미지가 너무 큽니다"
        429 -> "사용 한도 초과 · API 결제·잔여 할당량·요청 빈도를 확인하세요"
        in 500..599 -> "Provider 서버 오류 · 잠시 후 다시 보내세요"
        in 300..399 -> "예상하지 못한 연결 이동 · 네트워크를 확인하세요"
        else -> "요청 실패 · API 설정과 네트워크를 확인하세요"
    }
}
