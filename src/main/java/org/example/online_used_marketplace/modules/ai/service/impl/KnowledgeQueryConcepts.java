package org.example.online_used_marketplace.modules.ai.service.impl;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared business terminology normalization for lexical retrieval and reranking.
 */
final class KnowledgeQueryConcepts {

    private static final Map<String, Concept> CONCEPTS = concepts();
    private static final Pattern PERCENTAGE_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?\\s*%");
    private static final Pattern CHINESE_PERCENTAGE_PATTERN = Pattern.compile("百分之[零一二三四五六七八九十百两\\d.]+");
    private static final Pattern DISCOUNT_PATTERN = Pattern.compile("[零一二三四五六七八九十两半\\d.]+折");
    private static final Set<String> COVERAGE_TARGETS = Set.of(
            "@price", "@phone", "@gpu", "@laptop", "@camera", "@condition", "@evidence",
            "@repair", "@drone", "@citation", "@identifier_tampering", "@tobacco",
            "@controlled_weapon", "@policy_grounding", "@authenticity", "@off_platform",
             "@payment_safety", "@account", "@medical", "@usage_history", "@accessory",
             "@accessory_pricing", "@condition_price_gap",
             "@sim_card",
             "@insufficient", "@quantified_claim", "@price_boundary", "@market_evidence", "@seasonality",
            "@listing_identity", "@listing_permission");

    private KnowledgeQueryConcepts() {
    }

    static Set<String> detect(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        String raw = text.toLowerCase(Locale.ROOT);
        String normalized = normalize(raw);
        Set<String> result = new LinkedHashSet<>();
        CONCEPTS.forEach((name, concept) -> {
            if (concept.aliases().stream().anyMatch(normalized::contains)) {
                result.add(name);
            }
        });
        if (PERCENTAGE_PATTERN.matcher(raw).find()
                || CHINESE_PERCENTAGE_PATTERN.matcher(raw).find()
                || DISCOUNT_PATTERN.matcher(raw).find()) {
            result.add("@quantified_claim");
        }
        return result;
    }

    static Set<String> coverageTargets(Set<String> concepts) {
        Set<String> result = new LinkedHashSet<>();
        for (String concept : concepts) {
            if (COVERAGE_TARGETS.contains(concept)) {
                result.add(concept);
            }
        }
        return result;
    }

    static List<String> keywordExpansions(String text) {
        return keywordExpansions(detect(text));
    }

    static List<String> keywordExpansions(Set<String> concepts) {
        Set<String> result = new LinkedHashSet<>();
        for (String concept : concepts) {
            Concept configured = CONCEPTS.get(concept);
            if (configured != null) {
                result.addAll(configured.expansions());
            }
        }
        return new ArrayList<>(result);
    }

    static double weight(String concept) {
        return switch (concept) {
            case "@identifier_tampering", "@tobacco", "@controlled_weapon",
                 "@policy_grounding", "@sim_card" -> 3.0D;
            case "@payment_safety", "@price_boundary", "@market_evidence", "@seasonality",
                 "@listing_identity", "@listing_permission" -> 2.5D;
            case "@usage_history", "@accessory", "@off_platform", "@citation", "@evidence",
                 "@accessory_pricing", "@condition_price_gap", "@quantified_claim", "@drone" -> 2.0D;
            case "@phone", "@gpu", "@laptop", "@camera", "@repair",
                 "@authenticity", "@account", "@medical" -> 1.5D;
            case "@price" -> 0.8D;
            default -> 1.0D;
        };
    }

    static double coverageRatio(Set<String> targets, Set<String> covered) {
        if (targets == null || targets.isEmpty()) {
            return 1.0D;
        }
        double totalWeight = targets.stream().mapToDouble(KnowledgeQueryConcepts::weight).sum();
        double coveredWeight = covered == null ? 0.0D : covered.stream()
                .filter(targets::contains)
                .mapToDouble(KnowledgeQueryConcepts::weight)
                .sum();
        return totalWeight <= 0.0D ? 1.0D : Math.max(0.0D, Math.min(1.0D, coveredWeight / totalWeight));
    }

    static boolean hasCategoryConflict(Set<String> queryConcepts, Set<String> candidateConcepts) {
        Set<String> queryCategories = categories(queryConcepts);
        Set<String> candidateCategories = categories(candidateConcepts);
        if (queryCategories.isEmpty() || candidateCategories.isEmpty()) {
            return false;
        }
        return queryCategories.stream().noneMatch(candidateCategories::contains);
    }

    private static Set<String> categories(Set<String> concepts) {
        Set<String> result = new LinkedHashSet<>();
        for (String concept : concepts) {
            if (Set.of("@phone", "@gpu", "@laptop", "@camera", "@drone").contains(concept)) {
                result.add(concept);
            }
        }
        return result;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private static Map<String, Concept> concepts() {
        Map<String, Concept> out = new LinkedHashMap<>();
        out.put("@price", concept(
                List.of("价格", "定价", "报价", "估价", "估值", "成交价", "成交价格", "成交记录", "市场成交", "便宜", "折扣", "议价", "均价", "价差", "多少钱", "折价", "最低成交", "成交承诺", "固定金额", "下调", "降价", "调价"),
                List.of("价格", "定价", "报价", "估价", "成交价")));
        out.put("@phone", concept(
                List.of("手机", "iphone", "华为mate", "小米手机", "oppo", "vivo"),
                List.of("手机", "电池健康", "屏幕维修")));
        out.put("@gpu", concept(
                List.of("显卡", "rtx", "gtx", "radeon", "gpu"),
                List.of("显卡", "显存", "压力测试")));
        out.put("@laptop", concept(
                List.of("笔记本", "macbook", "游戏本", "轻薄本"),
                List.of("笔记本", "电池循环", "内存硬盘")));
        out.put("@camera", concept(
                List.of("相机", "镜头", "快门", "传感器"),
                List.of("相机", "快门次数", "传感器")));
        out.put("@camera_condition", concept(
                List.of("镜头灰尘", "镜头霉斑", "传感器灰点", "传感器污点", "相机成色规则", "相机镜头轻微灰尘",
                        "实拍样张", "影响成像", "是否有霉斑划痕"),
                List.of("相机镜头灰尘", "传感器灰点", "霉斑划痕", "实拍样张")));
        out.put("@condition", concept(
                List.of("成色", "新旧", "准新", "九成新", "95新", "99新", "挺新", "全新", "划痕", "磨损", "外观",
                        "灰点", "霉点", "霉斑", "磕碰", "凹陷", "裂纹", "裂痕", "亮斑", "亮点", "暗点", "坏点",
                        "掉色", "褪色", "打油", "接触不良", "瑕疵", "破损", "变形", "漏液", "耗电明显", "续航异常"),
                List.of("成色", "使用痕迹", "瑕疵", "划痕", "磨损", "灰点", "霉斑")));
        out.put("@physical_damage", concept(
                List.of("磕碰", "凹陷", "裂纹", "裂痕", "破损", "变形", "缺口", "崩边"),
                List.of("磕碰", "凹陷", "裂纹", "近距离图片", "功能影响")));
        out.put("@monitor_condition", concept(
                List.of("显示器", "坏点", "暗点", "纯色背景", "红色背景", "绿色背景", "蓝色背景"),
                List.of("显示器坏点", "暗点", "坏点位置", "纯色背景照片")));
        out.put("@keyboard_condition", concept(
                List.of("键盘", "键帽", "空格键", "打油", "按键掉色", "字符磨损"),
                List.of("键盘按键掉色", "键帽磨损", "打油", "输入功能")));
        out.put("@watch_condition", concept(
                List.of("机械表", "手表", "表镜", "表冠", "表壳", "表带", "走时"),
                List.of("手表划痕", "表镜", "表冠", "走时", "按键功能")));
        out.put("@earphone_condition", concept(
                List.of("耳机", "左耳", "右耳", "左右耳", "充电盒", "补电一次", "播放时长"),
                List.of("耳机电池", "左右耳耗电", "单次播放", "充电盒续航")));
        out.put("@condition_general", concept(
                List.of("二手商品成色描述规范", "成色描述模板", "真实使用痕迹", "配件完整度和购买时间"),
                List.of("二手商品成色描述规范", "成色描述模板", "使用痕迹", "配件完整度")));
        out.put("@repair", concept(
                List.of("维修", "修过", "修好", "拆修", "炸机", "炸过", "返修", "故障", "碰撞", "进水"),
                List.of("维修", "维修历史", "故障", "碰撞")));
        out.put("@drone", concept(
                List.of("无人机", "航拍器", "飞机", "飞控", "炸机", "炸过"),
                List.of("无人机", "炸机", "飞控")));
        out.put("@evidence", concept(
                List.of("发票", "凭证", "购买记录", "证据", "证明", "披露", "实拍", "鉴定", "截图", "检测", "资料"),
                List.of("凭证", "证据", "披露", "检测截图")));
        out.put("@citation", concept(
                List.of("来源文档", "哪份规则", "规则来源", "命中片段", "命中文档", "引用覆盖", "证据回传", "返回来源", "不展示来源", "没有给出来源", "未标来源", "没有标来源", "来源不明", "无来源", "未注明出处", "没有注明出处", "哪段原文", "规则冲突", "结论依据", "文档版本", "相关原文", "命中依据", "无法指出", "省略来源", "结论不一致", "相反要求", "可追溯依据", "追溯依据", "引用证据", "展示证据", "新旧规范", "新旧规则", "版本有效性"),
                List.of("风险证据回传", "来源文档", "命中片段", "证据不足")));
        out.put("@identifier_tampering", concept(
                List.of("imei", "串号", "设备标识", "设备识别码", "序列号", "改码", "改串号", "设备锁", "绕锁", "绕过设备锁"),
                List.of("改串号", "设备标识修改", "imei", "绕过设备锁", "禁售")));
        out.put("@tobacco", concept(
                List.of("电子烟", "烟弹", "雾化弹", "烟油", "雾化器", "烟草替代品"),
                List.of("电子烟", "烟弹", "雾化弹", "烟油", "禁售")));
        out.put("@controlled_weapon", concept(
                List.of("管制刀具", "军刀", "开刃", "弹簧刀", "匕首", "刀具收藏", "户外刀"),
                List.of("管制刀具", "军刀", "开刃刀具", "收藏品", "禁售")));
        out.put("@policy_grounding", concept(
                List.of("知识库没写", "知识库没有", "库里找不到", "规则库没有", "超出知识库", "参考常识", "凭常识", "根据经验推断", "编一条", "写一条", "编规则", "编平台规则", "平台条款", "没有规则来源", "没有任何规则来源", "没有依据", "外部常识", "借用常识", "从未出现", "知识未覆盖", "新设备"),
                List.of("知识问答拒答", "知识库证据不足", "不得编造平台规则", "要求补充依据")));
        out.put("@authenticity", concept(
                List.of("高仿", "原单", "复刻", "正品", "假一赔", "专柜验货"),
                List.of("正品凭证", "鉴定", "真实性")));
        out.put("@off_platform", concept(
                List.of("微信", "vx", "v信", "手机号", "电话号码", "电话联系", "私聊", "线下联系", "线下付款", "私下付款", "私下转账", "私下补", "私下", "收款码", "支付宝", "不走平台", "站外", "平台外", "脱离平台", "先款后货"),
                List.of("站外交易", "站外联系方式", "私人电话号码", "私下转账", "平台内支付")));
        out.put("@payment_safety", concept(
                List.of("付款", "支付", "转账", "收款", "收款码", "订金", "定金", "尾款", "运费差价", "支付宝", "担保交易", "平台担保", "货到付款", "先款后货", "货款", "现金", "付现", "汇款", "打款", "银行卡", "个人账户", "个人卡", "二维码", "红包", "pos机", "押金", "保证金", "补偿款", "结算"),
                List.of("交易安全", "平台担保支付", "私下转账", "沟通与支付记录")));
        out.put("@account", concept(
                List.of("账号", "账户", "找回", "封号", "封禁", "虚拟货币", "实名绑定", "初始实名", "换绑", "本人使用", "所有权", "充值凭证", "角色转让"),
                List.of("账号交易", "找回风险", "实名绑定")));
        out.put("@medical", concept(
                List.of("处方药", "药品", "医疗器械", "保健品"),
                List.of("药品禁售", "医疗商品")));
        out.put("@sim_card", concept(
                List.of("流量卡", "物联网卡", "电话卡", "手机卡", "sim卡", "永久套餐", "无限流量", "不限速", "不实名"),
                List.of("通信卡发布风险", "流量卡", "物联网卡", "实名要求", "资费期限", "不限速承诺")));
        out.put("@usage_history", concept(
                List.of("矿卡", "非矿", "挖矿", "使用历史", "高负载", "温度", "跑分", "烤机"),
                List.of("矿卡历史", "长期高负载", "维修历史", "工作温度", "压力测试")));
        out.put("@accessory", concept(
                List.of("配件", "附件", "只有机身", "仅机身", "仅主机", "无原盒", "没原盒", "原盒", "丢失原盒", "原盒丢失",
                        "充电器", "充电线", "非原装", "不是原装", "少充电器", "少配件", "不带充电器", "配件不齐",
                        "包装", "包装盒", "箱说", "镜头盖", "保修卡", "缺保修", "无保修", "没有电池", "缺电池", "无电池",
                        "原装电池", "缺一块原装电池", "少一块原装电池"),
                List.of("缺少原装配件", "无原盒", "非原装", "仅主机", "替代成本", "配件清单")));
        out.put("@accessory_pricing", concept(
                List.of("缺少原装配件定价", "配件定价", "配件价值", "附件价值", "替代成本", "配件缺失成本", "配件完整度影响价格"),
                List.of("缺少原装配件定价影响", "配件价值", "替代成本", "配件清单")));
        out.put("@packaging_missing", concept(
                List.of("包装缺失", "无原盒", "没原盒", "丢失原盒", "原盒丢失", "原盒已经丢失", "实际配件清单", "误认为全套"),
                List.of("包装缺失", "无原盒", "仅主机", "实际配件清单", "避免误认为全套")));
        out.put("@pricing_factors", concept(
                List.of("定价需结合", "定价应结合", "估价需结合", "估价应结合", "估价应参考", "报价需结合",
                        "报价应结合", "价格建议需要", "定价因素", "估价规则", "定价规则", "定价参考",
                        "折价规则", "报价调整规则", "价格区间", "补充关键参数"),
                List.of("定价因素", "估价规则", "配置成色维修配件", "真实成交参考")));
        out.put("@condition_price_gap", concept(
                List.of("95新和99新价差", "九五新和九九新价差", "成色价差", "品相价差", "成色与价格", "外观痕迹价差",
                        "成色统一折价", "品相统一折价", "外观等级折价", "按成色统一", "季节折价"),
                List.of("95新和99新价差", "成色价差", "外观痕迹", "不能固定比例")));
        out.put("@insufficient", concept(
                List.of("不知道", "不清楚", "未知", "不能证明", "无法证明", "缺少", "没有提供", "信息不足", "证据不足", "不能确定", "只知道", "能直接判断", "能不能卖", "能直接估价", "能报精确价格", "确定价格", "没有成交数据", "没有近期成交", "库里找不到", "知识库没写", "参考常识", "外部常识", "借用常识", "从未出现", "知识未覆盖", "根据经验推断", "编一条", "写一条", "编平台规则", "没有规则来源", "没有依据", "全部未知", "无法确认", "知识范围之外", "没有对应条款", "没有上下文", "身份无法确定", "已过期", "旧规则"),
                List.of("证据不足", "无法确定", "补充关键信息", "不得编造平台规则")));
        out.put("@quantified_claim", concept(
                List.of("统一便宜", "固定比例", "统一折扣", "百分比", "折扣比例", "议价空间", "编造折扣", "精确价格", "精确报价", "%"),
                List.of("价格忠实度", "固定比例", "不得编造", "精确报价")));
        out.put("@price_boundary", concept(
                List.of("固定比例", "固定金额", "统一下调", "统一调整", "统一折扣", "统一加价", "统一减价", "统一便宜", "直接按原价", "按原价", "都固定按", "据此统一", "是否可靠", "是否合理", "不得编造", "不能统一", "不能固定", "固定系数", "统一涨幅", "相同减价", "直接换算", "一律", "精确到元", "同一百分比", "相同百分比", "同一比例", "最低成交承诺", "成交承诺", "价格边界"),
                List.of("价格忠实度", "不得编造", "固定比例", "统一加价", "真实成交证据")));
        out.put("@market_evidence", concept(
                List.of("成交样本", "成交数据", "近期成交", "市场数据", "市场行情", "挂牌价", "挂价", "同款成交", "参考价", "实际成交", "成交资料", "回收网站", "在售价", "市场样本"),
                List.of("近期市场成交", "真实成交样本", "挂牌价不能替代成交价")));
        out.put("@seasonality", concept(
                List.of("开学季", "淡季", "旺季", "促销季", "节假日", "新品发布", "季节因素", "季节波动"),
                List.of("淡旺季价差", "开学季", "促销季", "近期成交")));
        out.put("@disclosure", concept(
                List.of("只说", "只写", "不写", "没写", "省略", "隐瞒", "披露", "如实说明", "描述中说明", "描述应包含", "需要说明",
                        "描述中注明", "注明", "应说明", "明确说明", "说明位置", "提供近距离图片", "配近距离照片", "建议补充外观",
                        "单独说明", "分开写", "正文", "详情页", "近照", "描述模板", "描述写法",
                        "成色说明", "成色判定", "卖的时候", "信息完整", "信息充分"),
                List.of("如实披露", "描述中说明", "实拍", "维修历史", "风险说明")));
        out.put("@listing_identity", concept(
                List.of("商品名称", "商品身份", "商品信息", "具体商品", "具体描述", "品类", "用途", "品牌型号", "没有品名", "身份无法确定"),
                List.of("商品名称", "品类用途", "品牌型号", "补充商品信息")));
        out.put("@listing_permission", concept(
                List.of("允许发布", "允许出售", "能不能卖", "能卖吗", "能不能发", "能否发布", "是否准售", "不能直接判断", "规则检索", "风险判断"),
                List.of("是否允许发布", "规则检索", "风险判断", "补充商品信息")));
        return Map.copyOf(out);
    }

    private static Concept concept(List<String> aliases, List<String> expansions) {
        return new Concept(List.copyOf(aliases), List.copyOf(expansions));
    }

    private record Concept(List<String> aliases, List<String> expansions) {
    }
}
