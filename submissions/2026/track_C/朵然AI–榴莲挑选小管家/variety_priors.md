# 常见品种先验资料库（v0）

该文档用于维护“品种预设范围”：

- 用于异常检测参照（例如重量/可食率明显偏离常见范围时降低置信度）
- 用于 CV 特征归一化基准（不同品种的“刺密度/形态”可能存在系统性差异）

注意：

- 先验不是结论，更不能替代照片测量；它只负责提供“合理范围”和“偏离程度”
- 同一品种在不同产地/树龄/成熟度下差异很大；本文给出的范围以“公开资料/论文可引用内容”为准

## 通用基础范围（Durio zibethinus 大多数栽培品种）

- 单果重量：通常 1–3 kg（也可能超过 3 kg）[[Wikipedia: Durian]](https://en.wikipedia.org/wiki/Durian)；[[FAO: Potential Fruits]](https://www.fao.org/4/ad523e/ad523e04.htm)
- 可食部分（果肉/aril/pulp）占整果质量：约 15–30% [[Wikipedia: Durian]](https://en.wikipedia.org/wiki/Durian)
- 果实裂瓣/分段：成熟时通常从顶部裂开为 4–6 段 [[FAO: Potential Fruits]](https://www.fao.org/4/ad523e/ad523e04.htm)

映射建议：

- “房数”更接近“分段/果室数量”的用户化表达；在没有更精细标注之前，建议把它当作近似/主观参数，用于辅助解释而非硬约束。

## 品种条目（与 App 枚举对齐）

| App 品种 | 常用名/别名 | 重量先验（公开资料） | 出肉先验（公开资料） | 形态/房数先验（公开资料） | 备注 |
|---|---|---|---|---|---|
| 金枕 | Monthong | 研究样本在 120 DAFB 记录 fruit weight 3652.30 g（成熟度实验条件）[[MDPI 2025: Monthong]](https://www.mdpi.com/2311-7524/11/4/432) | 若缺少品种专属数据，先用通用 15–30% | 暂无直接“金枕专属”公开量化：先用通用 4–6 段（并由 CV 形态特征覆盖） | Monthong 的先验建议先从重量分布开始，出肉率优先靠照片+参数估计 |
| 猫山王 | Musang King（D197） | harvest 平均 2161.8 ± 74.7 g（90 DAFS）[[De Gruyter 2025: Musang King]](https://www.degruyter.com/document/doi/10.1515/opag-2025-0422/html) | 果肉占比 24.8%（同上）[[De Gruyter 2025: Musang King]](https://www.degruyter.com/document/doi/10.1515/opag-2025-0422/html) | 暂无统一“房数/分段”专属定量：先用通用 4–6 段 | 论文同时给出 husk 70.1%、seed 5.2%，对“可食率”建模很有用 |
| 黑刺 | Black Thorn（D200） | 暂缺可引用的公开论文/官方统计：先用通用 1–3 kg；结合自采数据校准 | 暂缺：先用通用 15–30% | 暂缺：先用通用 4–6 段 | 建议后续优先建立自有数据：按产地/树龄分组统计重量与可食率 |
| D24 | D24（常见马来系克隆） | 暂缺可引用的公开论文/官方统计：先用通用 1–3 kg；结合自采数据校准 | 暂缺：先用通用 15–30% | 暂缺：先用通用 4–6 段 | 文献中常作为“推荐栽培品种”被提及，但公开量化（重量/可食率）并不集中 |
| 托曼尼 | （待确认英文名/克隆号） | 暂缺：先用通用 1–3 kg | 暂缺：先用通用 15–30% | 暂缺：先用通用 4–6 段 | 建议先把“托曼尼”的标准命名与产地/供应链标签对齐，再建先验库 |
| 其他/未知 | - | 使用通用范围 | 使用通用范围 | 使用通用范围 | 当品种未知时，先做通用模型；品种识别置信度足够高再切换到品种先验 |

## 来源补充（可用于后续扩展）

- FAO 文档给了 durian 的形态学描述（重量可能超过 3 kg、成熟裂为 4–6 段）[[FAO: Potential Fruits]](https://www.fao.org/4/ad523e/ad523e04.htm)
- Wikipedia 汇总了“可食部分约占 15–30%”等通用事实（适合作为“通用先验”）[[Wikipedia: Durian]](https://en.wikipedia.org/wiki/Durian)
- Musang King 的重量与各部分占比（husk/flesh/seed）存在可引用的量化研究，可直接进入我们的“可食率先验”[[De Gruyter 2025: Musang King]](https://www.degruyter.com/document/doi/10.1515/opag-2025-0422/html)
- Monthong 的成熟度研究提供了不同 DAFB 的重量峰值数据，可用于“成熟度→重量→质构/甜度”映射[[MDPI 2025: Monthong]](https://www.mdpi.com/2311-7524/11/4/432)

