package com.georu.examples.demo.multi.dto;

import com.georu.core.plugin.MigrationId;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 회원 연락처 (target-1 tb_member_contact): tb_member 와 같은 소스에서 연락처만 */
@Data
@NoArgsConstructor
public class TbMemberContactDto {

    @MigrationId
    private Integer memberId;

    private String email;

    private String phone;
}
