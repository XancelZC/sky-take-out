package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import io.swagger.models.auth.In;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class
OrderServiceImpl implements OrderService {

    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private DishMapper dishMapper;

    /**
     * 提交订单
     * @param ordersSubmitDTO
     * @return
     */
    @Transactional
    @Override
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        //异常情况的处理（收货地址为空、超出配送范围、购物车为空）
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null){
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        //查询当前用户的购物车数据
        Long userId = BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);

        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        if (shoppingCartList == null || shoppingCartList.isEmpty()){
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        //构造订单数据
        Orders order = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,order);
        order.setNumber(String.valueOf(System.currentTimeMillis()));
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setUserId(userId);
        order.setOrderTime(LocalDateTime.now());
        order.setPayStatus(Orders.UN_PAID);
        order.setPhone(addressBook.getPhone());
        order.setAddress(addressBook.getDetail());
        order.setConsignee(addressBook.getConsignee());
        //向订单表插入1条数据
        orderMapper.insert(order);
        //订单明细数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart cart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart,orderDetail);
            orderDetail.setOrderId(order.getId());
            orderDetailList.add(orderDetail);
        }
        //向明细表插入n条数据
        orderDetailMapper.insertBatch(orderDetailList);
        //清理购物车中的数据
        shoppingCartMapper.cleanShoppingCart(userId);
        //封装返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(order.getId())
                .orderNumber(order.getNumber())
                .orderAmount(order.getAmount())
                .orderTime(order.getOrderTime())
                .build();

        return orderSubmitVO;
    }

    /* 订单支付
     * @param ordersPaymentDTO
     * @return
     */
    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception{
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        //调用微信支付接口，生成预支付交易单
        /*JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), //商户订单号
                new BigDecimal(0.01), //支付金额，单位 元
                "苍穹外卖订单", //商品描述
                user.getOpenid() //微信用户的openid
        );

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }*/

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("code", "ORDERPAID");
        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        //为替代微信支付成功后的数据库订单状态更新，多定义一个方法进行修改
        Integer OrderPaidStatus = Orders.PAID; //支付状态，已支付
        Integer OrderStatus = Orders.TO_BE_CONFIRMED;  //订单状态，待接单

        //发现没有将支付时间 check_out属性赋值，所以在这里更新
        LocalDateTime check_out_time = LocalDateTime.now();

        //获取订单号码
        String orderNumber = ordersPaymentDTO.getOrderNumber();

        log.info("调用updateStatus，用于替换微信支付更新数据库状态的问题");
        orderMapper.updateStatus(OrderStatus, OrderPaidStatus, check_out_time, orderNumber);

        return vo;
    }


    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();

        // 根据订单号查询当前用户的订单
        Orders ordersDB = orderMapper.getByNumberAndUserId(outTradeNo, userId);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);
    }

    /**
     * 查询历史订单 （分页查询）
     * @param page
     * @param pageSize
     * @param status
     * @return
     */
    @Override
    public PageResult listHistoryOrders(int page, int pageSize, Integer status){

        //设置分页
        PageHelper.startPage(page,pageSize);
        //DTO作用：在不同层之间传输数据
        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        Page<Orders> page1 =  orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList<>();

        if (page1 != null && page1.getTotal() > 0){
            for (Orders orders : page1) {

                Long orderId = orders.getId();

                List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orderId);
                //将查询到的order复制给vo 用来前端展示
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders,orderVO);
                orderVO.setOrderDetailList(orderDetailList);
                //加入创建的vo集合
                list.add(orderVO);
            }
        }
        //注意分页查询的返回结果都是PageResult
        return new PageResult(page1.getTotal(),list);
    }

    /**
     * 查询订单详情
     * @param id
     * @return
     */
    @Override
    public OrderVO getOrderDetail(Long id) {

        OrderVO orderVO = new OrderVO();

        Orders order = orderMapper.getById(id);
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        BeanUtils.copyProperties(order,orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        //得到菜品信息字符串
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            String orderDish = x.getName() + "*" + x.getNumber() + ";";
            return orderDish;
        }).collect(Collectors.toList());
        String orderDishString = String.join("", orderDishList);
        orderVO.setOrderDishes(orderDishString);

        return orderVO;
    }

    /**
     * 取消订单
     * @param id
     * @throws Exception
     */
    @Override
    public void cancel(Long id) throws Exception {
        //获取order
        Orders orderDB= orderMapper.getById(id);
        //处理异常情况
        if (orderDB == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        //1待付款 2待接单 3已接单 4派送中 5已完成 6已取消 7退款
        //如果订单目前已经处于接单后的状态，取消订单需要联系商家处理
        if (orderDB.getStatus() > 2){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        //处理完异常情况后，正常情况：如果未付款 直接取消订单 如果已付款 需要进行退款处理

        Orders orders = new Orders();
        orders.setId(id);

        //如果已付款 待接单 进行退款
        if (orderDB.getStatus().equals(Orders.TO_BE_CONFIRMED)){
            //暂时没有微信商户号 故先注释
//            weChatPayUtil.refund(order.getNumber(),order.getNumber(),order.getAmount(),order.getAmount());
            orders.setPayStatus(Orders.REFUND);
        }

        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    /**
     * 再来一单
     * @param id
     */
    @Override
    public void repetition(Long id) {
        //再来一单 首先根据id获取当前订单 然后将订单内的菜品加到购物车里

        Long userId = BaseContext.getCurrentId();

        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        //Java Stream流式处理
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(x -> {
            ShoppingCart shoppingCart = new ShoppingCart();
            //复制属性 排除id
            BeanUtils.copyProperties(x, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());
            return shoppingCart;
        }).collect(Collectors.toList());

        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 订单搜索
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(),ordersPageQueryDTO.getPageSize());

        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);
        List<Orders> ordersList = page.getResult();

        List<OrderVO> orderVOList = new ArrayList<>();

        if (!ordersList.isEmpty()){
            for (Orders orders : ordersList) {
                OrderVO orderVO = new OrderVO();

                BeanUtils.copyProperties(orders,orderVO);
                //得到菜品信息字符串
//                List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
//                List<String> orderDishList = orderDetailList.stream().map(x -> {
//                    String orderDish = x.getName() + "*" + x.getNumber() + ";";
//                    return orderDish;
//                }).collect(Collectors.toList());
//                String orderDishString = String.join("", orderDishList);
//
//                orderVO.setOrderDishes(orderDishString);
                orderVOList.add(orderVO);
            }
        }
        return new PageResult(page.getTotal(),orderVOList);
    }

    /**
     * 各个状态的订单数量统计
     * @return
     */
    @Override
    public OrderStatisticsVO statistics() {
        Integer confirmed = orderMapper.getStatistics(Orders.CONFIRMED);
        Integer deliveryInProgress	 = orderMapper.getStatistics(Orders.DELIVERY_IN_PROGRESS);
        Integer toBeConfirmed	 = orderMapper.getStatistics(Orders.TO_BE_CONFIRMED);

        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);

        return orderStatisticsVO;
    }
}
