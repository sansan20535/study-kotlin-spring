package com.study.db.product

import com.study.db.product.fixture.ProductFixture
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
// @DataJpaTest 기본 트랜잭션 비활성화 및 작업 스레드별 독립 트랜잭션 보장
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class LockTest(
    private val productRepository: ProductRepository,
    private val entityManager: EntityManager,
    private val transactionTemplate: TransactionTemplate,
) {
    companion object {
        private const val CONCURRENT_REQUEST_COUNT = 5
    }

    private val executor = Executors.newFixedThreadPool(CONCURRENT_REQUEST_COUNT)
    private lateinit var product: Product

    // 테스트 상품 준비
    @BeforeEach
    fun setUp() {
        product = productRepository.saveAndFlush(ProductFixture.create())
    }

    // 스레드와 테스트 데이터 정리
    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
        productRepository.deleteAll()
    }

    @Test
    fun `낙관적 락은 같은 버전을 수정한 트랜잭션 중 하나만 성공시킨다`() {
        // 모든 트랜잭션의 동일 버전 조회 완료 및 동시 커밋 시작 제어
        val allTransactionsLoaded = CountDownLatch(CONCURRENT_REQUEST_COUNT)
        val allowCommit = CountDownLatch(1)

        // 5개 트랜잭션의 동시 조회 및 커밋 진행
        println("\n=== 낙관적 락: 동시에 같은 버전을 수정 ===")
        val futures = (1..CONCURRENT_REQUEST_COUNT).map {
            submitOptimisticStockDecrease(allTransactionsLoaded, allowCommit)
        }
        check(allTransactionsLoaded.await(3, TimeUnit.SECONDS))
        println("[테스트] ${CONCURRENT_REQUEST_COUNT}개 트랜잭션 조회 완료, 동시에 커밋 진행")
        allowCommit.countDown()

        // 트랜잭션별 성공 여부와 낙관적 락 충돌 개수 확인
        val failures = futures.mapIndexed { index, future ->
            val result = runCatching { future.get(3, TimeUnit.SECONDS) }
            val failedByOptimisticLock = result.exceptionOrNull()?.let(::hasOptimisticLockCause) == true
            println("[테스트] 트랜잭션-${index + 1}: ${if (failedByOptimisticLock) "버전 충돌" else "커밋 성공"}")
            failedByOptimisticLock
        }.count { it }

        // 하나의 트랜잭션만 성공한 결과 검증
        val savedProduct = productRepository.findById(product.id).orElseThrow()
        println("[테스트] 최종 상태: stock=${savedProduct.stock}, version=${savedProduct.version}")
        assertThat(failures).isEqualTo(CONCURRENT_REQUEST_COUNT - 1)
        assertThat(savedProduct.stock).isEqualTo(9)
    }

    @Test
    fun `비관적 락은 앞선 트랜잭션이 끝날 때까지 다음 트랜잭션을 대기시킨다`() {
        // 첫 번째 락 획득과 나머지 트랜잭션의 대기 시점 제어
        val firstLockAcquired = CountDownLatch(1)
        val releaseFirstTransaction = CountDownLatch(1)
        val waitingTransactionCount = CONCURRENT_REQUEST_COUNT - 1
        val waitingTransactionsStarted = CountDownLatch(waitingTransactionCount)

        // 첫 번째 트랜잭션의 비관적 락 선점
        println("\n=== 비관적 락: 먼저 획득한 트랜잭션이 행 잠금 ===")
        val first = executor.submit {
            transactionTemplate.executeWithoutResult {
                println("[트랜잭션-1] 비관적 쓰기 락 요청")
                val loadedProduct = entityManager.find(
                    Product::class.java,
                    product.id,
                    LockModeType.PESSIMISTIC_WRITE,
                )
                println("[트랜잭션-1] 락 획득: stock=${loadedProduct.stock}")
                firstLockAcquired.countDown()
                check(releaseFirstTransaction.await(3, TimeUnit.SECONDS))
                loadedProduct.decreaseStock(1)
                println("[트랜잭션-1] 재고 감소 완료: stock=${loadedProduct.stock}")
            }
        }

        // 나머지 4개 트랜잭션의 비관적 락 요청
        check(firstLockAcquired.await(3, TimeUnit.SECONDS))
        val waitingFutures = (2..CONCURRENT_REQUEST_COUNT).map { transactionNumber ->
            executor.submit {
                transactionTemplate.executeWithoutResult {
                    waitingTransactionsStarted.countDown()
                    println("[트랜잭션-$transactionNumber] 비관적 쓰기 락 요청 후 대기")
                    val loadedProduct = entityManager.find(
                        Product::class.java,
                        product.id,
                        LockModeType.PESSIMISTIC_WRITE,
                    )
                    println("[트랜잭션-$transactionNumber] 락 획득: stock=${loadedProduct.stock}")
                    loadedProduct.decreaseStock(1)
                    println("[트랜잭션-$transactionNumber] 재고 감소 완료: stock=${loadedProduct.stock}")
                }
            }
        }

        // 첫 번째 트랜잭션의 락으로 인한 나머지 작업의 대기 검증
        check(waitingTransactionsStarted.await(3, TimeUnit.SECONDS))
        waitingFutures.forEach { future ->
            assertThatThrownBy { future.get(300, TimeUnit.MILLISECONDS) }
                .isInstanceOf(java.util.concurrent.TimeoutException::class.java)
        }
        println("[테스트] 나머지 ${waitingTransactionCount}개 트랜잭션 대기 확인, 트랜잭션-1 커밋 허용")

        // 첫 번째 락 해제 후 나머지 트랜잭션의 순차 처리 완료
        releaseFirstTransaction.countDown()
        first.get(3, TimeUnit.SECONDS)
        waitingFutures.forEach { it.get(3, TimeUnit.SECONDS) }

        // 모든 트랜잭션이 성공한 결과 검증
        val savedProduct = productRepository.findById(product.id).orElseThrow()
        println("[테스트] 최종 상태: stock=${savedProduct.stock}, version=${savedProduct.version}")
        assertThat(savedProduct.stock).isEqualTo(5)
    }

    // 별도 트랜잭션에서 낙관적 락으로 상품 조회 후 재고 감소
    private fun submitOptimisticStockDecrease(
        allTransactionsLoaded: CountDownLatch,
        allowCommit: CountDownLatch,
    ) = executor.submit<Unit> {
        transactionTemplate.executeWithoutResult {
            val loadedProduct = entityManager.find(
                Product::class.java,
                product.id,
                LockModeType.OPTIMISTIC,
            )
            allTransactionsLoaded.countDown()
            check(allowCommit.await(3, TimeUnit.SECONDS))
            loadedProduct.decreaseStock(1)
        }
    }

    // 예외 체인 내부의 낙관적 락 예외 탐색
    private fun hasOptimisticLockCause(throwable: Throwable): Boolean =
        generateSequence(throwable) { it.cause }
            .any {
                it is ObjectOptimisticLockingFailureException ||
                    it is jakarta.persistence.OptimisticLockException
            }
}
