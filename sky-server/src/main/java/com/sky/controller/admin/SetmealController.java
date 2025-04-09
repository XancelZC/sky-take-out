package com.sky.controller.admin;

import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.SetmealVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/admin/setmeal")
@Api(tags = "套餐相关接口")
public class SetmealController {

    @Autowired
    private SetmealService setmealService;

    @PostMapping
    @ApiOperation("添加套餐功能")
    public Result add(@RequestBody SetmealDTO setmealDTO){
        log.info("添加套餐：{}",setmealDTO);
        setmealService.saveWithDish(setmealDTO);
        return null;
    }

    @GetMapping("/page")
    @ApiOperation("分页查询套餐")
    public Result<PageResult> pageQuery(SetmealPageQueryDTO setmealPageQueryDTO){
        log.info("分页查询套餐:{}",setmealPageQueryDTO);
        PageResult pageResult = setmealService.pageQuery(setmealPageQueryDTO);
        return Result.success(pageResult);
    }

    @DeleteMapping
    @ApiOperation("删除套餐功能")
    public Result delete(@RequestParam List<Long> ids){
        log.info("删除套餐 id：{}",ids);
        setmealService.deleteBatch(ids);
        return Result.success();
    }

    //完成修改套餐功能 需要数据回显前端
    @GetMapping("/{id}")
    @ApiOperation("根据id查询套餐功能")
    public Result<SetmealVO> getSetmealById(@PathVariable Long id){
        log.info("根据id查询套餐：{}",id);
        SetmealVO setmealVO = setmealService.getSetmealById(id);
        return Result.success(setmealVO);
    }

    @PutMapping
    @ApiOperation("修改套餐功能")
    public Result update(@RequestBody SetmealDTO setmealDTO){
        log.info("修改套餐功能：{}",setmealDTO);
        setmealService.update(setmealDTO);
        return Result.success();
    }


    @PostMapping("/status/{status}")
    @ApiOperation("套餐起售停售功能")
    public Result startOrStop(@PathVariable Integer status, Long id){
        log.info("{}id套餐起售停售{}",id,status);
        setmealService.startOrStop(status,id);
        return Result.success();
    }
}
