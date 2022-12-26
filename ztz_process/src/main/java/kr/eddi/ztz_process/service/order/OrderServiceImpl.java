package kr.eddi.ztz_process.service.order;

import com.siot.IamportRestClient.IamportClient;
import com.siot.IamportRestClient.exception.IamportResponseException;
import com.siot.IamportRestClient.request.CancelData;
import kr.eddi.ztz_process.controller.order.form.OrderInfoRegisterForm;
import kr.eddi.ztz_process.controller.order.request.RefundRequest;
import kr.eddi.ztz_process.controller.order.request.ChangeOrderStateRequest;
import kr.eddi.ztz_process.entity.member.Address;
import kr.eddi.ztz_process.entity.member.Member;
import kr.eddi.ztz_process.entity.order.Payment;
import kr.eddi.ztz_process.entity.products.Product;
import kr.eddi.ztz_process.repository.member.MemberRepository;
import kr.eddi.ztz_process.repository.order.PaymentRepository;
import kr.eddi.ztz_process.repository.products.ProductsRepository;
import kr.eddi.ztz_process.entity.order.OrderInfo;
import kr.eddi.ztz_process.repository.order.OrderInfoRepository;
import kr.eddi.ztz_process.service.order.request.PaymentRegisterRequest;
import kr.eddi.ztz_process.service.security.RedisService;
import kr.eddi.ztz_process.utility.order.setRandomOrderNo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService{
    private final Integer MINORDERNUM = 1;
    private final Integer MAXORDERNUM = 99999;

    @Autowired
    OrderInfoRepository orderRepository;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    RedisService redisService;
    @Autowired
    ProductsRepository productsRepository;

    @Autowired
    MemberRepository memberRepository;

    @Override
    public Boolean registerOrderInfo(PaymentRegisterRequest paymentRegisterRequest) {
        try {
             //결제 정보 저장
            Payment payment = registerPayment(paymentRegisterRequest);

            OrderInfoRegisterForm OrderListInfo = paymentRegisterRequest.getSendInfo();

            // 주문 번호 생성
            String setOrderNum = MakeOrderedNo(OrderListInfo.getMemberID().get(0));

            for (int i = 0; i < OrderListInfo.getProductID().size(); i++) {
                Optional<Product> maybeProduct = productsRepository.findById(OrderListInfo.getProductID().get(i));
                Optional<Member> maybeMember = memberRepository.findById(OrderListInfo.getMemberID().get(i));

                OrderInfo orderInfo = OrderInfo
                        .builder()
                        .orderNo(setOrderNum)
                        .orderCnt(OrderListInfo.getOrderCnt().get(i))
                        .orderState("결제완료")
                        .product(maybeProduct.get())
                        .member(maybeMember.get())
                        .payment(payment)
                        .build();
                orderRepository.save(orderInfo);
            }
            return true;
        }catch (Exception e){
            System.out.println("오류 발생" + e);
            return false;
        }
    }
    @Override
    public List<OrderInfo> readAllOrders(Long PaymentId){

        List<OrderInfo> orderInfo = orderRepository.findByPaymentId(PaymentId);

        System.out.println("주문 아이디"+ orderInfo.get(0).getOrderID());
        System.out.println("주문 개수"+orderInfo.get(0).getOrderCnt());
        System.out.println("상품 이름"+orderInfo.get(0).getProduct().getName());
        System.out.println("결제 정보"+orderInfo.get(0).getPayment().getImp_uid());
        return orderInfo;
    }

    @Override
    public List<Payment> readAllPayment(String token){
        Long id = redisService.getValueByKey(token);
        Member member = memberRepository.findByMemberId(id);
        System.out.println("맴버 번호"+ member.getId());


        List<Payment> payments = paymentRepository.findAllByMemberId(member.getId());

        return payments;
    }

    @Override
    public List<Payment> readManagerAllPayment(){
        List<Payment> payments = paymentRepository.findAll(Sort.by(Sort.Direction.DESC, "paymentId"));
        return payments;
    }

    @Override
    public Boolean refundAllOrder(RefundRequest refundRequest) throws IOException {
        String test_api_key = "2701111347244503";
        String test_api_secret = "J7xV8xenAUsYtgiuUjwAJNJ7o2Ax4VnSsaABT0G04YDwedek5x0Rzp0e1elG2od4sZTyUzVygtxUUwnp";
        IamportClient client = new IamportClient(test_api_key,test_api_secret);

        Optional<Payment> maybePayment = paymentRepository.findById(refundRequest.getRefundPaymentId());
        Payment payment = maybePayment.get();
        List<OrderInfo> orderInfos = orderRepository.findByPaymentId(payment.getPaymentId());
        String uid = payment.getImp_uid();
        System.out.println(uid);
        CancelData cancelData = new CancelData(uid,true);

        try {
            client.cancelPaymentByImpUid(cancelData);

            payment.setPaymentState("전액 취소 완료");
            for (int i = 0; i < orderInfos.size(); i++) {
                orderInfos.get(i).setOrderState("환불 완료");
                orderInfos.get(i).setRefundReason(refundRequest.getRefundReason());
                orderRepository.save(orderInfos.get(i));
            }
            paymentRepository.save(payment);
        }catch (IamportResponseException e){
            System.out.println("결제 취소 실패");
            System.out.println("오류 :" + e);
        }
        return true;
    }

    public Payment registerPayment(PaymentRegisterRequest paymentRegisterRequest){
        OrderInfoRegisterForm OrderListInfo = paymentRegisterRequest.getSendInfo();
        Integer TotalOrderedCnt = 0;
        Optional<Member> maybeMember = memberRepository.findById(OrderListInfo.getMemberID().get(0));
        for (int i = 0; i < paymentRegisterRequest.getSendInfo().getOrderCnt().size(); i++) {
            TotalOrderedCnt += paymentRegisterRequest.getSendInfo().getOrderCnt().get(i);
        }

        Address address = Address.of(
                paymentRegisterRequest.getCity(),
                paymentRegisterRequest.getStreet(),
                paymentRegisterRequest.getAddressDetail(),
                paymentRegisterRequest.getZipcode()
        );

        Payment payment = Payment.
                builder()
                .merchant_uid(paymentRegisterRequest.getMerchant_uid())
                .totalPaymentPrice(paymentRegisterRequest.getPaymentPrice())
                .imp_uid(paymentRegisterRequest.getImp_uid())
                .OrderedCnt(TotalOrderedCnt)
                .PaymentState("결제 완료")
                .address(address)
                .DeliveryRequest(paymentRegisterRequest.getSendRequest())
                .member(maybeMember.get())
                .build();

        paymentRepository.save(payment);

        return payment;
    }
    public String MakeOrderedNo(Long memberID){
        LocalDate createdDateAt;
        createdDateAt = LocalDate.now();

        String setOrderNum;

        while (true){
            setOrderNum = String.valueOf(setRandomOrderNo.makeIntCustomRandom(MINORDERNUM , MAXORDERNUM));
            Optional<OrderInfo> maybeOrderNo = orderRepository.findByOrderNo(setOrderNum);
            if (maybeOrderNo.isPresent()){
                System.out.println("주문 아이디 재생성" + maybeOrderNo.get());
            }else {
                setOrderNum += "-" + createdDateAt + "-";
                setOrderNum += memberID;
                break;
            }
        }



        return setOrderNum;
    }

    public List<OrderInfo> changeOrderState(ChangeOrderStateRequest changeOrderStateRequest){

        //배송시작, 배송완료, 구매확정, 반품신청 4개 reqType
        String reqType = changeOrderStateRequest.getReqType();
        String stateName ="";
        Boolean allSameOrderStateCheck = false;
        int sameOrderStateNum = 0;

        if(reqType.equals("배송시작")){
            stateName = "배송중";
        } else if (reqType.equals("배송완료")) {
            stateName = "배송완료";
        } else if (reqType.equals("구매확정")) {
            stateName = "구매확정";
        } else if(reqType.equals("반품신청")){
            stateName = "반품신청";
        }

        // orderinfo는 그냥 각각 배송시작 상태 변경해주기
        Optional<OrderInfo> maybeOrderInfo = orderRepository.findById(changeOrderStateRequest.getOrderId());
        OrderInfo startOrderInfo = maybeOrderInfo.get();
        startOrderInfo.setOrderState(stateName);

        orderRepository.save(startOrderInfo);

        //위에서 개별적으로 상태변경 후! 만약 if paymentid 동일한 그룹 내 -> "배송중" 상태 몇개인지 반복문 돌리기
        List<OrderInfo> findOrderInfoPaymentList = orderRepository.findByPaymentId(changeOrderStateRequest.getPaymentId());

        for (int i = 0; i < findOrderInfoPaymentList.size(); i++) {
            if(findOrderInfoPaymentList.get(i).getOrderState().equals(stateName)){
                sameOrderStateNum = sameOrderStateNum + 1;
            }
        }
        if(findOrderInfoPaymentList.size() == sameOrderStateNum){
            allSameOrderStateCheck = true;
        }

        //payment - 여기는 여러개 모든 paymentId의 구성요소 orderInfo들이 다 배송시작 된 경우 payment 그룹 상태 변경 -> 배송중
        //payment 그룹 내 1개라도 배송중인 경우 부분배송 중으로 상태값 변경
        Optional<Payment> maybePayment = paymentRepository.findById(changeOrderStateRequest.getPaymentId());
        Payment payment = maybePayment.get();

        if (allSameOrderStateCheck){
            payment.setPaymentState(stateName);
        }else {
            payment.setPaymentState("부분 "+ stateName);
        }
        paymentRepository.save(payment);

        return findOrderInfoPaymentList;
    }
}
